# Yamcs Mission Control [![Build Status](https://travis-ci.org/yamcs/yamcs.svg?branch=master)](https://travis-ci.org/yamcs/yamcs) ![Maven Central](https://img.shields.io/maven-central/v/org.yamcs/yamcs.svg?label=release)

* Website: https://www.yamcs.org
* Mailing list: [Google Groups](https://groups.google.com/group/yamcs/)

Yamcs is a mission control framework developed in Java. It uses an open-ended architecture that allows tailoring its feature set using yaml configuration files. You can also extend the default feature set by writing custom Java classes.

To start developing your own Yamcs application, we recommend the [yamcs-maven-plugin](https://www.yamcs.org/yamcs-maven/yamcs-maven-plugin).


## Documentation

* Server Manual: https://www.yamcs.org/docs/server/
* Javadoc: https://www.yamcs.org/yamcs/javadoc/


## License

Yamcs is licensed under Affero GPLv3.

For commercial licensing please contact [Space Applications Services](https://www.spaceapplications.com) with your use case.


## Development Setup

To work on the core components of Yamcs you need JDK8, Maven and yarn.

Build Java jars:

    mvn clean install -DskipTests

Build web interface:

    cd yamcs-web
    yarn install
    yarn build
    cd ..

These commands will produce an optimized production version of the web interface. This process will take a few minutes. For faster incremental builds refer to instructions under `yamcs-web`.

For demo and development purposes we work with an all-in-one simulation environment that uses many Yamcs features. In this simulation, Yamcs receives TM from a simple simulator of a landing spacecraft. Yamcs can also send some basic TC. The simulator starts together with Yamcs as a subprocess.

    ./run-simulation.sh

This configuration stores data to `/storage/yamcs-data`. Ensure this folder exists and that you can write to it.

When Yamcs started successfully, you can visit the built-in web interface by navigating to `http://localhost:8090`.


## Contributions

While Yamcs is managed and developed by Space Applications Services, we also consider pull requests from other contributors. For non-trivial patches we ask you to sign our [CLA](https://www.yamcs.org/assets/Yamcs_Contributor_Agreement_v1.pdf). You need to do this only once.
