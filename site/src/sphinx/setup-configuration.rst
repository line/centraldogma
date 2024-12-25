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
      "trustedProxyAddresses": null,
      "clientAddressSources": null,
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "maxRemovedRepositoryAgeMillis": null,
      "repositoryCacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "webAppEnabled": true,
      "webAppTitle": null,
      "gracefulShutdownTimeout": {
        "quietPeriodMillis": 1000,
        "timeoutMillis": 10000
      },
      "replication": {
        "method": "NONE"
      },
      "writeQuotaPerRepository": {
        "requestQuota": 5,
        "timeWindowSeconds": 1
      },
      "accessLogFormat": "common",
      "authentication": null,
      "cors": null,
      "pluginConfigs": [
        {
          "type": "com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig",
          "enabled": true,
          "numMirroringThreads": null,
          "maxNumFilesPerMirror": null,
          "maxNumBytesPerMirror": null
        }
      ],
      "management": {
        "address": "127.0.0.1",
        "port": 36463,
        "protocol": null,
        "path": null
      },
      "zone": null
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

- ``trustedProxyAddresses`` (string array)

  - the addresses or ranges of `Classless Inter-domain Routing (CIDR) <https://tools.ietf.org/html/rfc4632>`_
    blocks of trusted proxy servers. e.g. ``10.0.0.1`` for a single address or ``10.0.0.0/8`` for a CIDR block.
    With ``trustedProxyAddresses`` and ``clientAddressSources`` properties, you can get a client address
    who initiated a request from the access log. If ``null`` or an empty array, the remote address of
    the connection is used as a client address.

- ``clientAddressSources`` (string array)

  - the HTTP header names to be used for retrieving a client address. ``PROXY_PROTOCOL`` is a reserved keyword
    for getting the source address specified in a
    `PROXY protocol <https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt>`_ message.
    By default, ``forwarded``, ``x-forwarded-for`` and ``PROXY_PROTOCOL`` are used when
    ``trustedProxyAddresses`` is configured. Otherwise, the remote address of the connection is used
    as a client address.

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

- ``maxRemovedRepositoryAgeMillis`` (integer)

  - the maximum allowed age of removed projects and repositories before they are purged.
    Set 0 to disable automatic purge.
    If ``null``, the default value of '604800000 milliseconds' (7 days) is used.

- ``repositoryCacheSpec`` (string)

  - the cache specification string which determines the capacity and behavior of the repository
    access cache. Refer to `the Caffeine API documentation`_ for more information.
    Note that the weight of the cache has been tuned to be roughly proportional to its memory usage.

- ``webAppEnabled`` (boolean)

  - whether to enable the web-based administrative console. Enabled by default.

- ``webAppTitle`` (string)

  - the title text which is displayed on the navigation bar of the web-based administrative console.
    If ``null``, the default value of ``Central Dogma at {{hostname}}`` is used. Note that ``{{hostname}}``
    will be replaced with the actual hostname that the server is running on.

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

-  ``writeQuotaPerRepository``

   - the maximum allowed write quota per repository. If ``requestQuota`` is set to 5 and
     ``timeWindowSeconds`` is set to 1, :ref:`pushing-a-commit`` cannot exceed 5 QPS; if exceeded,
     `429 Too Many Requests <https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/429>`_ will be returned.
     If ``null``, no limit is enforced.

   - ``requestQuota`` (integer)

     - a maximum number of acceptable requests.

   - ``timeWindowSeconds`` (integer)

     - a time windows in seconds.

- ``accessLogFormat`` (string)

  - the format to be used for writing an access log. ``common`` and ``combined`` are pre-defined for NCSA
    common log format and NCSA combined log format, respectively. Also, a custom log format can be specified
    here. Read `Writing an access log <https://line.github.io/armeria/docs/server-access-log>`_ for more
    information. Specify ``null`` to disable access logging feature.

- ``authentication``

  - the authentication configuration. If ``null``, the authentication is disabled.
    See :ref:`auth` to learn how to configure the authentication layer.

- ``cors``

  - specify ``null`` to disable CORS entirely.
  - ``allowedOrigins`` (string or string array)

    - the list of origins which are allowed a lenient CORS policy. If the literal value ``*`` is specified then
      all origins are allowed.

  - ``maxAgeSeconds`` (integer)

    - how long in seconds the results of a preflight request can be cached. If not specified then the default
      value ``7200`` is applied.

- ``pluginConfigs``

  - the list of plugin configuration. See :ref:`plugins` for more information.

- ``management``

  - the management server configuration. Read `ManagementService API documentation <https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/management/ManagementService.html>`_
    to know more about the management service.

  - ``port`` (integer)

    - the port number of the management service.
      If ``0``, the management service uses the same port as the main service.

  - ``address`` (string)

    - the IP address of the management service. If ``null``, the management will listen to all network interfaces.

    - this option is ignored if ``port`` is set to ``0``.

  - ``protocol``

    - the protocol of the management service. ``http`` and ``https`` are supported. If not specified, ``http`` is used.

    - this option is ignored if ``port`` is set to ``0``.

  - ``path``

    - the path of the management service. If not specified, the management service is mounted at ``/internal/management``.

- ``zone``

    - the zone information of the server. If not specified, ``PluginTarget.ZONE_LEADER_ONLY`` can't be used.

    - ``currentZone`` (string)

      - the current zone name. If the value starts with ``env:``, the environment variable is used as the zone name.
        For example, if the value is ``env:ZONE_NAME``, the environment variable named ``ZONE_NAME`` is used as the
        zone name.

      - You can also dynamically load a zone name by implementing :api:`com.linecorp.centraldogma.server.ConfigValueConverter`.

    - ``allZones`` (string array)

      - the list of zone names.

      - the current zone name must be included in the list of zone names.

.. _replication:

Configuring replication
-----------------------
Central Dogma features multi-master replication which allows a client to push commits to any of the available
replicas, and thus it’s possible to update the settings of your application even when all replicas but one are
down. The clients will automatically connect to an available replica.

.. note::

    Central Dogma implements multi-master replication by embedding `Apache ZooKeeper <https://zookeeper.apache.org>`_.
    You may find it useful to have some prior administrative knowledge of ZooKeeper although it is not required.
    For more information about ZooKeeper administration, see
    `ZooKeeper administrator's guide <https://zookeeper.apache.org/doc/r3.4.10/zookeeperAdmin.html>`_

To enable replication, you need to update the ``replication`` section of ``conf/dogma.json``. The following
example shows the configuration of the first replica in a 3-replica cluster:

.. code-block:: json

    {
      ...
      "replication" : {
        "method": "ZOOKEEPER",
        "serverId": 1,
        "servers": {
          "1": {
            "host": "replica1.example.com",
            "quorumPort": 36463,
            "electionPort": 36464,
            "groupId": null,
            "weight": null
          },
          "2": {
            "host": "replica2.example.com",
            "quorumPort": 36463,
            "electionPort": 36464,
            "groupId": null,
            "weight": null
          },
          "3": {
            "host": "replica3.example.com",
            "quorumPort": 36463,
            "electionPort": 36464,
            "groupId": null,
            "weight": null
          }
        },
        "secret": "JqJAkZ!oZ6MNx4rBpIH8M*yuVWXDULgR",
        "additionalProperties": {},
        "timeoutMillis": null,
        "numWorkers": null,
        "maxLogCount": null,
        "minLogAgeMillis": null
      }
    }

- ``method`` (string)

  - the replication method. ``ZOOKEEPER`` indicates Central Dogma will provide multi-master replication by
    embedding Apache ZooKeeper.

- ``serverId`` (integer)

  - the unique positive integer ID of the replica. Be careful not to use a duplicate ID or not to change
    this value after joining the cluster. If ``null`` or unspecified, the ``serverId`` is auto-detected
    from the server list in the ``servers`` section.

    .. note::

        Internally, this value is used as the ``myid`` of the embedded ZooKeeper peer.

- ``servers``

  - a map whose key is the ``serverId`` of a replica in the cluster and whose value is a map which
    contains the properties required to connect to each other:

    - ``host`` (string)

      - the host name or IP address of the replica

    - ``quorumPort`` (integer)

      - the TCP/IP port number which is used by ZooKeeper for reaching consensus

    - ``electionPort`` (integer)

      - the TCP/IP port number which is used by ZooKeeper for leader election

    - ``groupId`` (integer)

      - the group ID which is used by ZooKeeper for
        `hierarchical quorums <https://zookeeper.apache.org/doc/r3.5.8/zookeeperHierarchicalQuorums.html>`_
        If ``null`` or unspecified, hierarchical quorums are disabled.

    - ``weight`` (integer)

      - the weight of the replica which is used by ZooKeeper for hierarchical quorums
        If ``null`` or unspecified, ``1`` is used by default.
        If ``groupId`` is ``null``, this value will be ignored.

  - It is highly recommended to have more than 3, preferably odd number of, replicas because the consensus
    algorithm requires more than half of all replicas to agree with each other to function correctly.
    If you had 2 replicas, losing just one replica would make your cluster stop to function.

    .. note::

       See `here <http://bytecontinnum.com/2016/09/zookeeper-always-configured-odd-number-nodes/>`_ or
       `here <https://www.quora.com/HBase-Why-we-run-zookeeper-with-odd-number-of-instance>`_ if you are
       curious why odd number of replicas are preferred over even number of replicas.

- ``secret`` (string)

  - the secret string which is used for replicas to authenticate each other. The replicas in the same
    cluster must have the same secret. If ``null`` or unspecified, the default value of ``ch4n63m3``
    is used. You can secure this property by :ref:`hiding_sensitive_property_values`.

- ``additionalProperties`` (map of string key-value pairs)

  - ZooKeeper `configuration properties <https://zookeeper.apache.org/doc/current/zookeeperAdmin.html#sc_configuration>`_.
    Currently, ``initLimit``, ``syncLimit``, ``tickTime``, ``syncEnabled``, ``autopurge.snapRetainCount``,
    ``autopurge.purgeInterval``, and ``quorumListenOnAllIPs`` can be set.
    It is recommended to leave this properties empty because Central Dogma sets the sensible defaults.

- ``timeoutMillis`` (integer)

  - the ZooKeeper timeout, in milliseconds. If ``null`` or unspecified, the default value of
    '1000 milliseconds' (1 second) is used.

- ``numWorkers`` (integer)

  - the number of worker threads dedicated for replication. If ``null`` or unspecified, the default value
    of '16 threads' is used.

- ``maxLogCount`` (integer)

  - the maximum number of log items to keep in ZooKeeper. Note that the log entries will still not be removed
    if they are younger than ``minLogAgeMillis``. If ``null`` or unspecified, the default value of
    '1024 log entries' is used.

- ``minLogAgeMillis`` (integer)

  - the minimum allowed age of log items before they are removed from ZooKeeper. If ``null`` or unspecified,
    the default value of '86400000 milliseconds' (1 day) is used.

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
        "keyCertChain": "file:./cert/centraldogma.crt",
        "key": "file:./cert/centraldogma.key",
        "keyPassword": null
      },
      "trustedProxyAddresses": null,
      "clientAddressSources": null,
      "numWorkers": null,
      "maxNumConnections": null,
      "requestTimeoutMillis": null,
      "idleTimeoutMillis": null,
      "maxFrameLength": null,
      "numRepositoryWorkers": 16,
      "repositoryCacheSpec": "maximumWeight=134217728,expireAfterAccess=5m",
      "webAppEnabled": true,
      "webAppTitle": null,
      "gracefulShutdownTimeout": {
        "quietPeriodMillis": 1000,
        "timeoutMillis": 10000
      },
      "replication": {
        "method": "NONE"
      },
      "accessLogFormat": "common",
      "authentication": null
    }

- ``tls``

  - the configuration for TLS support. It will be applied to the port which is configured with ``https``
    protocol. If ``null``, a self-signed certificate will be generated for ``https`` protocol.
  - ``keyCertChain`` (string)

    - the content of the certificate chain. If you want to use a file, specify ``file:<path>``.
      You can secure this property by :ref:`hiding_sensitive_property_values`.

  - ``key`` (string)

    - the content of the private key. If you want to use a file, specify ``file:<path>``.
      You can secure this property by :ref:`hiding_sensitive_property_values`.

  - ``keyPassword`` (string)

    - the password of the private key file. Specify ``null`` if no password is set. Note that ``null``
      (no password) and ``"null"`` (password is 'null') are different.
      You can secure this property by :ref:`hiding_sensitive_property_values`.

If you run your Central Dogma with TLS, you need to enable TLS on the client side as well. In case of
Java client, call the ``useTls()`` method when building a ``CentralDogma`` instance:

.. code-block:: java

    CentralDogma dogma = new ArmeriaCentralDogmaBuilder()
            .host("centraldogma.example.com", 8443)
            .accessToken("appToken-********")
            .useTls()
            .build();

.. _plugins:

Configuring plugins
-------------------
Central Dogma supports installing a plugin that runs on Central Dogma servers. You can configure the plugin
with ``pluginConfigs`` property in ``dogma.json`` as follows.

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
      ...
      "pluginConfigs": [
        {
          "type": "com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig",
          "enabled": true,
          "numMirroringThreads": null,
          "maxNumFilesPerMirror": null,
          "maxNumBytesPerMirror": null,
          "zonePinned": false
        }
      ]
    }

Each configuration in ``pluginConfigs`` must have ``type`` that specifies the fully qualified class name of
the plugin configuration class. The plugin configuration class must implement the ``PluginConfig`` interface
that requires ``enabled`` property. The plugin configuration class can have additional properties that are
specific to the plugin. The example above shows the configuration of the mirroring plugin and here are the
properties that can be configured:

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

- ``zonePinned`` (boolean)

   - whether the mirroring plugin is pinned to a specific zone. If ``true``, a mirroring task will be executed
     only in the specified zone. If ``false``, the plugin will be executed in the leader replica.

For more information about mirroring, refer to :ref:`mirroring`.

.. _hiding_sensitive_property_values:

Hiding sensitive property values
--------------------------------
Central Dogma supports property value substitution through the use of
:api:`com.linecorp.centraldogma.server.ConfigValueConverter`.
If you want to hide sensitive information such as ``tls.keyPasswords``, you can implement the converting logic
with your own :api:`com.linecorp.centraldogma.server.ConfigValueConverter` and register it via
`SPI <https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html>`_:

- Your configuration file:

.. code-block:: json

    {
      "tls": {
        "keyCertChain": "file:./cert/centraldogma.crt",
        "key": "file:./cert/centraldogma.key",
        "keyPassword": "encryption:encrypted-password"
      }
    }

- Your converter:

.. code-block:: java

    import com.linecorp.centraldogma.server.ConfigValueConverter;

    public class MyConfigValueConverter implements ConfigValueConverter {
        @Override
        public List<String> supportedPrefixes() {
            return List.of("encryption");
        }

        @Override
        public String convert(String prefix, String value) {
            assert "encryption".equals(prefix);
            if ("encrypted-password".equals(value)) {
                // return the decrypted password that is stored in a safe place such as Vault.
            }
            ...
        }
    }

This feature enables you to enhance the security of your Central Dogma configuration by avoiding the
exposure of sensitive information. Below is a list of properties that can be substituted:

- ``replication.secret``
- ``tls.keyCertChain``
- ``tls.key``
- ``tls.keyPassword``
- ``authentication.properties.keyStore.password`` (when SAML is used.)
- ``authentication.properties.keyStore.keyPasswords`` (when SAML is used.)
