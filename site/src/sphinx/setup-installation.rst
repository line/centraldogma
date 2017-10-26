.. _setup-installation:

Installation
============

Prerequisites
-------------
Central Dogma server is a Java application that requires Java 8 or above. If your system has no Java installed,
you need to `download and install Java <http://www.oracle.com/technetwork/java/javase/downloads/>`_ first.
Once installed, make sure the ``java`` binary is in your ``PATH`` or the ``JAVA_HOME`` environment variable is
set correctly.

If you have a plan to use multi-master replication, you also need to set up an
`Apache ZooKeeper <https://zookeeper.apache.org>`_ cluster.
Refer to `the ZooKeeper administrator's guide <http://zookeeper.apache.org/doc/r3.4.10/zookeeperAdmin.html>`_
for more information.

.. note::

    You do not need to setup a ZooKeeper cluster right now. Central Dogma works without a ZooKeeper cluster
    as long as replication is disabled.

Download
--------
|download| the tarball and extract it into your preferred location:

.. parsed-literal::

    $ tar zxvf centraldogma-\ |release|\ .tgz

Startup and shutdown
--------------------
The distribution is shipped with a simple configuration with replication disabled, so you can play with it
immediately:

.. parsed-literal::

    $ cd centraldogma-\ |release|\ /
    $ bin/startup
    ...
    Started up centraldogma successfully: <pid>

    # Open http://127.0.0.1:36462/ in your browser for administrative console.

To stop the server, use the ``bin/shutdown`` script:

.. parsed-literal::

    $ bin/shutdown
    ...
    Shut down centraldogma successfully.

.. tip::

    If you are working with source code instead of binary distribution, you can use the Gradle tasks::

        $ ./gradlew startup
        $ ./gradlew shutdown

    You can also tail the log file::

        $ ./gradlew tail

Running on Docker
-----------------
You can also pull Central Dogma image from `Docker Hub <https://hub.docker.com/r/line/centraldogma/>`_
and then run it on your Docker::

    $ docker run -p 36462:36462 line/centraldogma

