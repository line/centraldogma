.. _`Apache Shiro`: https://shiro.apache.org/

.. _setup-configuration:

Configuration
=============
The main configuration file is located at ``conf/dogma.json``, with most properties set to their sensible
defaults:

.. code-block:: json

    {
      "dataDir": "./data",
      "ports": [
        {
          "localAddress": {
            "host": "*",
            "port": 36462
          },
          "protocol": "http"
        }
      ],
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "cacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "webAppEnabled": true,
      "gracefulShutdownTimeout": {
        "quietPeriodMillis": 1000,
        "timeoutMillis": 10000
      },
      "replication": {
        "method": "NONE"
      },
      "securityEnabled": false,
      "mirroringEnabled": true,
      "numMirroringThreads": null,
      "maxNumFilesPerMirror": null,
      "maxNumBytesPerMirror": null
    }

Core properties
---------------
- ``dataDir`` (string)

  - the path to the data directory which contains the repositories served by Central Dogma and
    other stateful data. Can be a relative path from the root directory of the distribution.

- ``ports``

  - the server ports that serve the incoming requests.
  - ``localAddress`` - the bind address of server socket.

    - ``host`` (string)

      - the IP address or ``*`` to bind to all network interfaces.

    - ``port`` (integer)

      - the port number. 36462 is preferred.

  - ``protocol`` (string)

    - the protocol. ``http`` is the only supported protocol at the moment.

- ``numWorkers`` (integer)

  - the number of I/O worker threads. If ``null``, the default value of ``<numCpuCores> * 2``
    is used.

- ``maxNumConnections`` (integer)

  - the maximum number of TCP/IP connections that can be handled concurrently.
    Any connection attempts that make the number of connections exceed this value will be rejected immediately.
    If ``null``, no limit is enforced.

- ``requestTimeoutMillis`` (integer)

  - the maximum number of milliseconds allowed for handling a request.
    If a request takes more than this, the server may respond with a ``503 Service Unavailable`` response.
    If ``null``, the default value of '10000 milliseconds' (10 seconds) is used.

- ``idleTimeoutMillis`` (integer)

  - the number of milliseconds before closing an idle connection.
    The server will close the connection if it stays idle for more than this without any pending requests.
    If ``null``, the default value of '15000 milliseconds' (15 seconds) is used.

- ``maxFrameLength`` (integer)

  - the maximum length of request content. If a client sends a request whose content
    is longer than this, the server may respond with a ``413 Request Entity Too Large`` response.
    If ``null``, the default value of '10485760 bytes' (10 MiB) is used.

- ``numRepositoryWorkers`` (integer)

  - the number of worker threads dedicated to handling repository reads and writes.
    If ``null``, the default value of '16 threads' is used.

- ``cacheSpec`` (string)

  - the cache specification string which determines the capacity and behavior of the repository
    access cache. Refer to `the Caffeine API documentation
    <https://static.javadoc.io/com.github.ben-manes.caffeine/caffeine/2.5.5/index.html?com/github/benmanes/caffeine/cache/CaffeineSpec.html>`_
    for more information. Note that the weight of the cache has been tuned to be roughly proportional to its
    memory usage.

- ``webAppEnabled`` (boolean)

  - whether to enable the web-based administrative console. Enabled by default.

- ``gracefulShutdownTimeout``

  - the amount of time to wait after the initiation of shutdown procedure for requests to go away before
    the actual shutdown of the server.
  - ``quietPeriodMillis`` (integer)

    - the number of milliseconds to wait for active requests to go end before shutting down.
      0 means the server will stop right away without waiting.

  - ``timeoutMillis`` (integer)

    - the number of milliseconds to wait before shutting down the server regardless of active requests.
      This should be set to a time greater than ``quietPeriodMillis`` to ensure the server shuts down
      even if there is a stuck request.

- ``replication``

  - the replication configuration.
  - ``method`` (string)

    - the replication method. ``NONE`` indicates 'standalone mode' without replication. ZooKeeper-based
      multi-master replication will be explained later in this page.

- ``securityEnabled`` (boolean)

  - whether to enable authentication. It's disabled by default so that a user can play with Central Dogma
    without hassle. However, it is strongly encouraged to enable authentication because the authorship of
    a commit is filled in automatically based on the principal of the current user. Central Dogma uses
    `Apache Shiro`_ as its authentication layer and uses the ``conf/security.ini`` file as its security
    configuration. For more information about how to configure `Apache Shiro`_, read
    `this page <https://shiro.apache.org/configuration.html#ini-sections>`_ or check the example configuration
    files under the ``conf/`` directory in the distribution.

- ``mirroringEnabled`` (boolean)

  - whether to enable Git-to-CD mirroring. It's enabled by default. For more information about mirroring,
    refer to :ref:`mirroring`.

- ``numMirroringThreads`` (integer)

  - the number of worker threads dedicated to periodic mirroring tasks. If ``null``, the default value of
    '16 threads' is used.

- ``maxNumFilesPerMirror`` (integer)

  - the maximum allowed number of files in a mirror. If a Git repository contains more files than this,
    Central Dogma will reject to mirror the Git repository. If ``null``, the default value of '8192 files'
    is used.

- ``maxNumBytesPerMirror`` (integer)

  - the maximum allowed number of bytes in a mirror. If the total size of the files in a Git repository exceeds
    this, Central Dogma will reject to mirror the Git repository. If ``null``, the default value of
    '33554432 bytes' (32 MiB) is used.

Configuring replication
-----------------------
Central Dogma features multi-master replication based on `Apache ZooKeeper <https://zookeeper.apache.org/>`_
for high availability. A client can write to any of the available replicas, and thus it’s possible to update
the settings of your application even when all replicas but one are down. The clients will automatically
connect to an available replica.

.. note::

    Don't have a working ZooKeeper cluster yet? Refer to
    `the ZooKeeper administrator's guide <http://zookeeper.apache.org/doc/r3.4.10/zookeeperAdmin.html>`_
    to set up one.

Once you have an access to a ZooKeeper cluster, update the ``replication`` section of ``conf/dogma.json``:

.. code-block:: json

    {
      "dataDir": "./data",
      "ports": [
        {
          "localAddress": {
            "host": "*",
            "port": 36462
          },
          "protocol": "http"
        }
      ],
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "cacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "webAppEnabled": true,
      "gracefulShutdownTimeout": {
        "quietPeriodMillis": 1000,
        "timeoutMillis": 10000
      },
      "replication" : {
        "method" : "ZOOKEEPER",
        "replicaId": "<replicaId>",
        "connectionString": "zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181",
        "pathPrefix": "/service/centraldogma",
        "timeoutMillis": null,
        "numWorkers": null,
        "maxLogCount": null,
        "minLogAgeMillis": null
      },
      "securityEnabled": false
    }

- ``method`` (string)

  - the replication method. ``ZOOKEEPER`` indicates ZooKeeper-based multi-master replication.

- ``replicaId`` (string)

  - the unique and unchanging ID of the replica, e.g. `UUID <https://www.uuidgenerator.net/>`_
  - Be extra cautious so that the replica IDs do not change or duplicate.

- ``connectionString`` (string)

  - the ZooKeeper connection string.

- ``pathPrefix`` (string)

  - the ZooKeeper path prefix. Central Dogma will create entries under this prefix.
  - Be extra cautious so that two different Central Dogma clusters never use the same path prefix
    at the same ZooKeeper cluster.

- ``timeoutMillis`` (integer)

  - the ZooKeeper timeout, in milliseconds. If ``null``, the default value of '1000 milliseconds' (1 second)
    is used.

- ``numWorkers`` (integer)

  - the number of worker threads dedicated for replication. If ``null``, the default value of '16 threads'
    is used.

- ``maxLogCount`` (integer)

  - the maximum number of log items to keep in ZooKeeper. Note that the log entries will still not be removed
    if they are younger than ``minLogAgeMillis``. If ``null``, the default value of '100 log entries' is used.

- ``minLogAgeMillis`` (integer)

  -  the minimum allowed age of log items before they are removed from ZooKeeper. If ``null`` the default
     value of '3600000 milliseconds' (1 hour) is used.
