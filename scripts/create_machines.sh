#! /usr/bin/env bash

# Usage: create_machines.sh <num-instances>

ZONE="us-east1-b"

FILESTORE_INSTANCE_ID="csi699"
FILESTORE_TIER="BASIC_HDD"
FILESTORE_FILE_SHARE_NAME="csi699"
FILESTORE_CAPACITY="1TiB"
FILESTORE_NETWORK="default"

COMPUTE_NAME_PATTERN="vm#"
COMPUTE_COUNT=$1
COMPUTE_MACHINE_TYPE="e2-standard-4"
COMPUTE_IMAGE_PROJECT="ubuntu-os-cloud"
COMPUTE_IMAGE_FAMILY="ubuntu-2204-lts"
COMPUTE_STARTUP_SCRIPT="scripts/startup.sh"

# maybe teardown previous filestore instance?

# create the filestore
echo "Step 1/7: Creating filestore instance..."
gcloud filestore instances create ${FILESTORE_INSTANCE_ID} \
    --tier=${FILESTORE_TIER} \
    --zone=${ZONE} \
    --file-share=name=${FILESTORE_FILE_SHARE_NAME},capacity=${FILESTORE_CAPACITY} \
    --network=name=${FILESTORE_NETWORK}

# get the filestore ip address and cidr information
echo "Step 2/7: Getting filestore IP address and CIDR range..."
FILESTORE_IP_ADDRESS=$(gcloud filestore instances describe --format="get(networks[ipAddresses][0])" ${FILESTORE_INSTANCE_ID})
FILESTORE_CIDR=$(gcloud filestore instances describe --format="get(networks[reservedIpRange])" ${FILESTORE_INSTANCE_ID})

# delete previous firewall rules if they already exist
echo "Step 3/7: Deleting existing NFS ingress firewall rule..."
echo "y" | gcloud compute firewall-rules delete nfs-ingress

echo "Step 4/7: Deleting existing NFS egress firewall rule..."
echo "y" | gcloud compute firewall-rules delete nfs-egress

# setup the new firwall rules using the new instance's ip address information
echo "Step 5/7: Creating new NFS ingress firewall rule..."
gcloud compute firewall-rules create nfs-ingress \
    --direction=INGRESS \
    --priority=1000 \
    --source-ranges=${FILESTORE_CIDR} \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:4045,udp:4045 \
    --network="default"

echo "Step 6/7: Creating new NFS egress firwall rule..."
gcloud compute firewall-rules create nfs-egress \
    --direction=EGRESS \
    --priority=1000 \
    --source-ranges=${FILESTORE_CIDR} \
    --action=ALLOW \
    --rules=tcp:111,tcp:2046,tcp:2049,tcp:2050,tcp:4045 \
    --network="default"
    
# maybe teardown previous machines?

# create the machines
# echo "Step 7/7: Creating ${COMPUTE_COUNT} virtual machines..."
gcloud compute instances bulk create \
    --name-pattern=${COMPUTE_NAME_PATTERN} \
    --zone=${ZONE} \
    --count=${COMPUTE_COUNT} \
    --machine-type=${COMPUTE_MACHINE_TYPE} \
    --image-project=${COMPUTE_IMAGE_PROJECT} \
    --image-family=${COMPUTE_IMAGE_FAMILY} \
    --metadata-from-file=startup-script=${COMPUTE_STARTUP_SCRIPT} \
    --metadata=filestore-ip=${FILESTORE_IP_ADDRESS}

# clone the repository into one of the virtual machines
gcloud compute scp 

echo "FINISHED!"