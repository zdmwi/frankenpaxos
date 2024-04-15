import requests
import time
import subprocess
import uuid
from google.cloud import oslogin_v1
from typing import Optional

SERVICE_ACCOUNT_METADATA_URL = (
    "http://metadata.google.internal/computeMetadata/v1/instance/"
    "service-accounts/default/email"
)

HEADERS = {"Metadata-Flavor": "Google"}

def execute(cmd: list[str], cwd: Optional[str] = None, capture_output: bool = False, env: Optional[dict] = None, raise_errors: bool = True) -> tuple[int, str]:
    """
    Run an external command (wrapper for Python subprocess)

    Args:
        cmd: The command to be run.
        cwd: Directory in which to run the command.
        capture_output: Whether to capture the output of the command.
        env: Environment variables passed the child process.
        raise_errors: Whether to raise errors if the command fails.

    Returns:
        Return code and captured output.
    """

    print(f"Running command: {cmd}")
    process = subprocess.run(
        cmd, 
        cwd=cwd,
        stdout=subprocess.PIPE if capture_output else subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
        text=True,
        env=env,
        check=raise_errors,
    )

    output = process.stdout
    return_code = process.returncode

    if return_code:
        print(f"Command failed with return code {return_code}")
        if capture_output:
            print(f"With output: {output}")

    return return_code, output


def create_ssh_key(oslogin_client: oslogin_v1.OsLoginServiceClient, account: str, expire_after: int = 300) -> str:
    """
    Generates a temporary SSH key pair and apply it to the specified account.
    
    Args:
        oslogin_client: The OsLoginServiceClient object.
        account: The name of the service account this key will be assigned to.
            This should be in the form of `user/<service_account_username>`.
        expire_after: How many seconds from now this key should be valid.


    Returns:
        The path to the private SSH key. Public key can be found by appending `.pub` to
        the file name.
    
    """

    private_key_file = f"/tmp/key-{uuid.uuid4()}"
    execute(["ssh-keygen", "-t", "rsa", "-N", "", "-f", private_key_file])

    with open(f"{private_key_file}.pub", "r") as original:
        public_key = original.read().strip()

    # expiration time is in microseconds.
    expiration = int((time.time() + expire_after) * 1_000_000)

    request = oslogin_v1.ImportSshPublicKeyRequest()
    request.parent = account
    request.ssh_public_key.key = public_key
    request.ssh_public_key.expiration_time_usec = expiration

    print(f"Setting key for {account}...")
    oslogin_client.import_ssh_public_key(request)

    # Let the key properly propagate
    print("Propagating key...")
    time.sleep(5)

    return private_key_file

def login_and_create_ssh_key(account: Optional[str] = None) -> str:
    """
    Logs in and creates the SSH key for the specified account.

    Args:
        account: The name of the service account the SSH key will be assigned to.

    Returns:
        The path to the private SSH key. Public key can be found by appending `.pub` to
        the file name.
    """

    oslogin = oslogin_v1.OsLoginServiceClient()
    account = (
        account or requests.get(SERVICE_ACCOUNT_METADATA_URL, headers=HEADERS).text
    )

    if not account.startswith("users/"):
        account = f"users/{account}"

    return create_ssh_key(oslogin, account)
    
