# Provisioning Virtual Machines

The original experiments were ran on AWS, but we make use of GCP due to availability. These are the setup steps for getting our experiment environment up and running and then torn down again when necessary. At the time of writing the default machines used were running **Debian 12**.

## Products Used
Make sure the following APIs are enabled for your account.

1. Google Cloud Compute Engine API
1. Filestore API

## Steps 
Instead of manually provisioning the number of machines we need and tearing them down each time, we will make use of the [Google Cloud CLI](https://cloud.google.com/sdk/gcloud/reference) to automate the steps.

Install the Google Cloud CLI here: https://cloud.google.com/sdk/docs/install

- Make sure you set the filestore instance to your default region. In my case, that's us-east-1b:
```bash
gcloud config set filestore/zone us-east-1b
```

- Create a filestore instance. The \<instance-id\> placeholder can be replaced by any string that satisifes their naming constraints and I'm using the network named "default":
```bash
gcloud filestore instances create example-filestore \
    --tier=BASIC_HDD \
    --file-share=name="csi699",capacity=1TiB \ # 1TiB is the minimum for the BASIC_HDD tier
    --network=name="default",
```

- Create the firewall rules to allow NFS to work correctly. See [here](https://cloud.google.com/filestore/docs/configuring-firewall) for more details. 
```bash
gcloud compute firewall-rules create nfs-ingress \
    --direction=INGRESS \
    --priority=1000 \ # the default priority
    --source-ranges=<filestore-instance-cidr-range> \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:4045,udp:4045 \
    --network="default"
```

```bash
gcloud compute firewall-rules create nfs-egress \
    --direction=EGRESS \
    --priority=1000 \ # the default priority
    --source-ranges=<filestore-instance-cidr-range> \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:2049,tcp:2050,tcp:4045 \
    --network="default"
```

- Get the IP address of the filestore.
```bash
gcloud filestore instances describe --format="get(networks[ipAddresses][0])" <instance-id>
```

- Create a single machine instance and mount the shared filestore. See [here](https://cloud.google.com/compute/docs/instances/create-start-instance#publicimage) for more details.
```bash
gcloud compute instances create <instance-name> \
    --machine-type=e2-standard-4 \ # General purpose machines with 4vCPUs and 16GB memory
    --image-project=ubuntu-os-cloud \ # If you want to use Ubunutu
    --image-family=ubuntu-2204-lts \
    --metadata-from-file=startup-script=scripts/startup.sh \
    --metadata=filestore-ip=<filestore-ip>
```
Note that the virtual machines created here use Ubuntu 22.04. The original experiment used machines running Ubuntu 18.04. No OS specific code was used in the implementation so this should be fine, but be aware this could be a source of problems later.

- Create multiple instances and mount the shared filestore. See [here](https://cloud.google.com/compute/docs/instances/multiple/create-in-bulk#create_vms_in_bulk_in_a_region) for more details. 
```bash
gcloud compute instances bulk create \
    --name-pattern="vm#" \
    --zone="us-east1-b" \
    --count=2 \
    --machine-type=e2-standard-4 \
    --image-project=ubuntu-os-cloud \ # If you want to use Ubuntu
    --image-family=ubuntu-2204-lts \
    --metadata-from-file=startup-script=scripts/startup.sh \
    --metadata=filestore-ip=<filestore-ip>
```

- Follow the instructions [here](https://cloud.google.com/filestore/docs/configuring-firewall) to setup the firewall to get NFS working right.


## Useful Commands

Verify the startup output of a created instance by SSH'ing into the machine and running the following command:

```bash
sudo journalctl -u google-startup-scripts.service
```

Need to have each node set the java path before running the program. We are not using an interactive SSH session so the .profile file does not
get sourced. So for each node SSH from the driver.


Start a benchmark experiment
```bash
python3 -m benchmarks.unreplicated.smoke -j /mnt/csi699/frankenpaxos-assembly-0.1.0-SNAPSHOT.jar -s /mnt/csi699/ -m -l info -i ~/.ssh/google_compute_engine --cluster benchmarks/unreplicated/local_cluster.json 
```

Experiments need to be run with elevated priveleges for networking stuff to work. 

