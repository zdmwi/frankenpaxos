#! /usr/bin/env bash

# Usage: initialize_driver.sh

# use coursier to install scala, sbt and java
curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup -y

# clone the project
git clone https://github.com/zdmwi/frankenpaxos.git

# install python dependencies
sudo apt-get install python3-pip python3.10-dev python3.10-venv gcc build-essential -yq

cd frankenpaxos && 
git checkout refactor &&
source ../.profile &&
cd benchmarks &&
pip3 install --no-warn-script-location -r requirements.txt 

# extract prometheus from tarball and start server
tar xvfz prometheus-*.tar.gz