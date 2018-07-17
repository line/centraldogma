.. _`Apache Shiro`: https://shiro.apache.org/
.. _`the Caffeine API documentation`: https://static.javadoc.io/com.github.ben-manes.caffeine/caffeine/2.6.2/com/github/benmanes/caffeine/cache/CaffeineSpec.html


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
          "protocols": [
            "http"
          ]
        }
      ],
      "tls": null,
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "repositoryCacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "sessionCacheSpec": "maximumSize=8192,expireAfterWrite=604800s",
      "webAppEnabled": true,
      "webAppSessionTimeoutMillis": 604800000,
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
      "maxNumBytesPerMirror": null,
      "accessLogFormat": "common",
      "administrators": [],
      "caseSensitiveLoginNames": false
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

  - ``protocols`` (string array)

    - protocols which are served on the port. ``http``, ``https`` and ``proxy`` are supported.

- ``tls``

  - the configuration for Transport Layer Security(TLS) support. Specify ``null`` to disable TLS.
    See :ref:`tls` for more information.

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

- ``repositoryCacheSpec`` (string)

  - the cache specification string which determines the capacity and behavior of the repository
    access cache. Refer to `the Caffeine API documentation`_ for more information.
    Note that the weight of the cache has been tuned to be roughly proportional to its memory usage.

- ``sessionCacheSpec`` (string)

  - the cache specification string which determines the capacity and behavior of the cache for the session
    information of the server. Refer to `the Caffeine API documentation`_ for more information.

- ``webAppEnabled`` (boolean)

  - whether to enable the web-based administrative console. Enabled by default.

- ``webAppSessionTimeoutMillis`` (integer)

  - the session timeout for web-based administrative console, in milliseconds. If ``null``, the default value
    of '604800000 milliseconds' (7 days) is used.

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

    - the replication method. ``NONE`` indicates 'standalone mode' without replication. See :ref:`replication`
      to learn how to configure ZooKeeper-based multi-master replication.

- ``securityEnabled`` (boolean)

  - whether to enable authentication. It's disabled by default so that a user can play with Central Dogma
    without hassle. However, it is strongly encouraged to enable authentication because the authorship of
    a commit is filled in automatically based on the principal of the current user. Central Dogma uses
    `Apache Shiro`_ as its authentication layer and uses the ``conf/shiro.ini`` file as its security
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

- ``accessLogFormat`` (string)

  - the format to be used for writing an access log. ``common`` and ``combined`` are pre-defined for NCSA
    common log format and NCSA combined log format, respectively. Also, a custom log format can be specified
    here. Read `Writing an access log <https://line.github.io/armeria/server-access-log.html>`_ for more
    information. Specify ``null`` to disable access logging feature.

- ``administrators`` (string array)

  - login IDs of the administrators. They are valid only if ``securityEnabled`` is ``true``.
    Please refer to :ref:`auth` for more information.

- ``caseSensitiveLoginNames`` (boolean)

  - whether case-sensitive matching is performed when login names are compared. Disabled by default
    (case-insensitive).

.. _replication:

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
          "protocols": [
            "http"
          ]
        }
      ],
      "tls": null,
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "repositoryCacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "sessionCacheSpec": "maximumSize=8192,expireAfterWrite=604800s",
      "webAppEnabled": true,
      "webAppSessionTimeoutMillis": 604800000,
      "gracefulShutdownTimeout": {
        "quietPeriodMillis": 1000,
        "timeoutMillis": 10000
      },
      "replication" : {
        "method" : "ZOOKEEPER",
        "connectionString": "zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181",
        "pathPrefix": "/service/centraldogma",
        "timeoutMillis": null,
        "numWorkers": null,
        "maxLogCount": null,
        "minLogAgeMillis": null
      },
      "securityEnabled": false,
      "accessLogFormat": "common",
      "administrators": [],
      "caseSensitiveLoginNames": false
    }

- ``method`` (string)

  - the replication method. ``ZOOKEEPER`` indicates ZooKeeper-based multi-master replication.

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

.. _tls:

Configuring TLS
---------------
Central Dogma supports TLS for its API and web pages. To enable TLS, a user may configure ``tls`` property
in ``dogma.json`` as follows.

.. code-block:: json

    {
      "dataDir": "./data",
      "ports": [
        {
          "localAddress": {
            "host": "*",
            "port": 36462
          },
          "protocols": [
            "https"
          ]
        }
      ],
      "tls": {
        "keyCertChainFile": "./cert/centraldogma.crt",
        "keyFile": "./cert/centraldogma.key",
        "keyPassword": null
      },
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "repositoryCacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "sessionCacheSpec": "maximumSize=8192,expireAfterWrite=604800s",
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
      "maxNumBytesPerMirror": null,
      "accessLogFormat": "common",
      "administrators": [],
      "caseSensitiveLoginNames": false
    }

- ``tls``

  - the configuration for TLS support. It will be applied to the port which is configured with ``https``
    protocol. If ``null``, a self-signed certificate will be generated for ``https`` protocol.
  - ``keyCertChainFile`` (string)

    - the path to the certificate chain file.

  - ``keyFile`` (string)

    - the path to the private key file.

  - ``keyPassword`` (string)

    - the password of the private key file. Specify ``null`` if no password is set. Note that ``null``
      (no password) and ``"null"`` (password is 'null') are different.

If you run your Central Dogma with TLS, you need to enable TLS of your ``CentralDogma`` client instance.
You can get it by ``CentralDogma.forTlsHost()`` methods.

.. code-block:: java

    CentralDogma dogma = CentralDogma.forTlsHost("centraldogma.example.com", 36462);

Also, ``CentralDogmaBuilder`` provides ``useTls()`` method.

.. code-block:: java

    CentralDogma dogma = new CentralDogmaBuilder().host("centraldogma.example.com", 36462)
                                                  .useTls()
                                                  .build();
