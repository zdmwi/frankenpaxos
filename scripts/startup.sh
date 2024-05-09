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

    sudo apt-get -yq update &&
    sudo apt-get -yq install nfs-common openjdk-8-jdk
    
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
}

main "$@"
