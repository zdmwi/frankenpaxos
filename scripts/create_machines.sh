#! /usr/bin/env bash

# Usage: create_machines.sh <num-instances>

ZONE="us-east1-b"

FILESTORE_INSTANCE_ID="csi699"
FILESTORE_TIER="BASIC_HDD"
FILESTORE_FILE_SHARE_NAME="csi699"
FILESTORE_CAPACITY="1TiB"
FILESTORE_NETWORK="default"

COMPUTE_NAME_PATTERN="vm##"
COMPUTE_COUNT=$1
COMPUTE_MACHINE_TYPE="e2-standard-4"
COMPUTE_IMAGE_PROJECT="ubuntu-os-cloud"
COMPUTE_IMAGE_FAMILY="ubuntu-2204-lts"
COMPUTE_STARTUP_SCRIPT="scripts/startup.sh"
COMPUTE_DRIVER_STARTUP_SCRIPT="scripts/driver-startup.sh"

# maybe teardown previous filestore instance?
NUM_STEPS=9

# create the filestore
echo "Step 1/${NUM_STEPS}: Creating filestore instance..."
gcloud filestore instances create ${FILESTORE_INSTANCE_ID} \
    --tier=${FILESTORE_TIER} \
    --zone=${ZONE} \
    --file-share=name=${FILESTORE_FILE_SHARE_NAME},capacity=${FILESTORE_CAPACITY} \
    --network=name=${FILESTORE_NETWORK}

# get the filestore ip address and cidr information
echo "Step 2/${NUM_STEPS}: Getting filestore IP address and CIDR range..."
FILESTORE_IP_ADDRESS=$(gcloud filestore instances describe --format="get(networks[ipAddresses][0])" ${FILESTORE_INSTANCE_ID})
FILESTORE_CIDR=$(gcloud filestore instances describe --format="get(networks[reservedIpRange])" ${FILESTORE_INSTANCE_ID})

# delete previous firewall rules if they already exist
echo "Step 3/${NUM_STEPS}: Deleting existing NFS ingress firewall rule..."
gcloud compute firewall-rules delete nfs-ingress --quiet

echo "Step 4/${NUM_STEPS}: Deleting existing NFS egress firewall rule..."
gcloud compute firewall-rules delete nfs-egress --quiet

# setup the new firwall rules using the new instance's ip address information
echo "Step 5/${NUM_STEPS}: Creating new NFS ingress firewall rule..."
gcloud compute firewall-rules create nfs-ingress \
    --direction=INGRESS \
    --priority=1000 \
    --source-ranges=${FILESTORE_CIDR} \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:4045,udp:4045 \
    --network="default"

echo "Step 6/${NUM_STEPS}: Creating new NFS egress firwall rule..."
gcloud compute firewall-rules create nfs-egress \
    --direction=EGRESS \
    --priority=1000 \
    --source-ranges=${FILESTORE_CIDR} \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:2049,tcp:2050,tcp:4045 \
    --network="default"


# create the machines
echo "Step 7/${NUM_STEPS}: Creating ${COMPUTE_COUNT} virtual machines..."

# create the driver node
gcloud compute instances create vm00 \
    --zone=${ZONE} \
    --machine-type=${COMPUTE_MACHINE_TYPE} \
    --image-project=${COMPUTE_IMAGE_PROJECT} \
    --image-family=${COMPUTE_IMAGE_FAMILY} \
    --metadata-from-file=startup-script=${COMPUTE_STARTUP_SCRIPT} \
    --metadata=filestore-ip=${FILESTORE_IP_ADDRESS}

# create the participating nodes
gcloud compute instances bulk create \
    --name-pattern=${COMPUTE_NAME_PATTERN} \
    --zone=${ZONE} \
    --count=${COMPUTE_COUNT} \
    --machine-type=${COMPUTE_MACHINE_TYPE} \
    --image-project=${COMPUTE_IMAGE_PROJECT} \
    --image-family=${COMPUTE_IMAGE_FAMILY} \
    --metadata-from-file=startup-script=${COMPUTE_STARTUP_SCRIPT} \
    --metadata=filestore-ip=${FILESTORE_IP_ADDRESS}

echo "Step 8/${NUM_STEPS}: Transferring ssh-keys to driver..."
# sleep for a bit to ensure that the node has spun up properly
sleep 20
# make sure the ssh-keys work project-wide
gcloud compute project-info add-metadata --metadata-from-file=ssh-keys=ssh-keys.txt

# remember to generate the ssh-key first
gcloud compute scp ~/.ssh/frankenpaxos* vm00:~/.ssh
gcloud compute scp ssh-keys.txt vm00:

# copy over the prometheus binary
gcloud compute scp prometheus-*.tar.gz vm00:frankenpaxos/

# install sbt, scala and java via coursier on the driver 
echo "Step 9/${NUM_STEPS}: Transferring initialization script to driver..."
INIT_SCRIPT_NAME="initialize_driver.sh"
gcloud compute scp scripts/${INIT_SCRIPT_NAME} vm00:
gcloud compute ssh vm00 --command="chmod +x ${INIT_SCRIPT_NAME} && ./${INIT_SCRIPT_NAME}"
gcloud compute scp target/scala-2.12/frankenpaxos-assembly-0.1.0-SNAPSHOT.jar vm00:/mnt/csi699

echo "FINISHED!"
