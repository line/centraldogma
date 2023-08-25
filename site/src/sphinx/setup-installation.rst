.. _setup-installation:

Installation
============

Prerequisites
-------------
Central Dogma server is a Java application that requires Java 11 or above. If your system has no Java installed,
you need to `download and install Java <https://adoptium.net/>`_ first.
Once installed, make sure the ``java`` binary is in your ``PATH`` or the ``JAVA_HOME`` environment variable is
set correctly.

Download
--------
|download| the tarball and extract it into your preferred location:

.. parsed-literal::
    :class: highlight-shell

    $ tar zxvf centraldogma-\ |release|\ .tgz

Startup and shutdown
--------------------
The distribution is shipped with a simple configuration with replication disabled, so you can play with it
immediately:

.. parsed-literal::
    :class: highlight-shell

    $ cd centraldogma-\ |release|\ /
    $ bin/startup
    ...
    Started up centraldogma successfully: <pid>
    <Open http://127.0.0.1:36462/ in your browser for administrative console.>

To stop the server, use the ``bin/shutdown`` script:

.. code-block:: shell

    $ bin/shutdown
    ...
    Shut down centraldogma successfully.

.. tip::

    If you are working with source code instead of binary distribution, you can use the Gradle tasks:

    .. code-block:: shell

        $ ./gradlew startup
        $ ./gradlew shutdown

Running on Docker
-----------------
You can also pull Central Dogma image from `Github Packages registry <https://github.com/line/centraldogma/pkgs/container/centraldogma>`_
and then run it on your Docker:

.. code-block:: shell

    $ docker run -p 36462:36462 ghcr.io/line/centraldogma

