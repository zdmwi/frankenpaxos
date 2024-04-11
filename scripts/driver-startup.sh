#! /usr/bin/env bash


function setup_ports() {
    # set the statd port if it isn't already set
    STATD_FILE=/etc/default/nfs-common
    STATD_PORT=2046
    if ! test -f "$STATD_FILE"; then
        touch "$STATD_FILE"
        echo "STATDOPTS=\"-p $STATD_PORT\"" >> "$STATD_FILE"
    fi

    # set nlockmgr port if it isn't already set
    NLOCKMGR_FILE=/etc/modprobe.d/lock.conf
    NLOCKMGR_PORT=4045
    if ! test -f "$NLOCKMGR_FILE"; then
        touch "$NLOCKMGR_FILE"
        echo "options lockd nlm_tcpport=$NLOCKMGR_PORT" >> "$NLOCKMGR_FILE"
        echo "options lockd nlm_udpport=$NLOCKMGR_PORT" >> "$NLOCKMGR_FILE"
    fi
}

function mount_filestore() {
    FILESTORE_IP_ADDRESS=$(curl http://metadata.google.internal/computeMetadata/v1/instance/attributes/filestore-ip -H "Metadata-Flavor: Google")
    
    echo "$FILESTORE_IP_ADDRESS"

    # Steps from: https://cloud.google.com/filestore/docs/mounting-fileshares#linux:-etcfstab

    sudo apt-get -y update &&
    sudo apt-get -y install nfs-common
    
    # make a local directory to map to the Filestore file share
    sudo mkdir -p mnt/csi699

    # append to the end of the file 
    sudo echo "${FILESTORE_IP_ADDRESS}:/csi699 /mnt/csi699 nfs defaults,_netdev 0 0" >> /etc/fstab

    # mount everything in /etc/fstab
    sudo mount -a

    # make the file share accessible for reading and writing
    sudo chmod go+rw /mnt/csi699
}

function main() {
    mount_filestore "$@"
    setup_ports "$@"
    
    curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup --yes
    sudo git clone https://github.com/zdmwi/frankenpaxos.git
}

main "$@"

# install python3.10-dev python3.10-venv gcc build-essential
# python -m benchmarks.unreplicated.smoke -s /home/zdw32/tmp -i ~/.ssh/id_rsa --cluster benchmarks/unreplicated/local_cluster.json
# ssh-add ~/.ssh/google_compute_engine
# gcloud compute ssh --ssh-flag="-A" vm0

# 3072 SHA256:qDwEZ3oiRqeeK287RNdYBppvanUmO+MI/sWWvWKJpns zidanewright@Zidanes-MacBook-Air.local (RSA)