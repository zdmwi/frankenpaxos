#! /usr/bin/env bash

# Usage: initialize_driver.sh

# use coursier to install scala, sbt and java
sudo curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup -y

# clone the project
sudo git clone https://github.com/zdmwi/frankenpaxos.git

# install python dependencies
sudo apt install python3.10-dev python3.10-venv gcc build-essential -y
cd frankenpaxos/benchmarks && pip3 install -r requirements.txt
