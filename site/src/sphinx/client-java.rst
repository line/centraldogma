.. _`Armeria`: https://line.github.io/armeria/
.. _`the API documentation`: apidocs/index.html

.. _client-java:

Java client library
===================
The Java client library is the first class client implementation that provides the access to all operations
exposed by a Central Dogma server.

.. tip::

    Keep `the API documentation`_ open in another browser tab. You'll find it to be a very useful companion.

Adding ``centraldogma-client`` as a dependency
----------------------------------------------
Gradle:

.. parsed-literal::
    :class: highlight-gradle

    dependencies {
        compile 'com.linecorp.centraldogma:centraldogma-client-armeria:\ |release|\ '
    }

Maven:

.. parsed-literal::
    :class: highlight-xml

    <dependencies>
      <dependency>
        <groupId>com.linecorp.centraldogma</groupId>
        <artifactId>centraldogma-client-armeria</artifactId>
        <version>\ |release|\ </version>
      </dependency>
    </dependencies>

Creating a client
-----------------
First, we should create a new instance of :api:`com.linecorp.centraldogma.client.CentralDogma`:

.. code-block:: java

    import com.linecorp.centraldogma.client.CentralDogma;
    import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;

    // The default port 36462 is used if unspecified.
    CentralDogma dogma = new ArmeriaCentralDogmaBuilder()
            .host("127.0.0.1")
            .build();
    // You can specify an alternative port or enable TLS as well:
    CentralDogma dogma2 = new ArmeriaCentralDogmaBuilder()
            .useTls()                   // Enable TLS.
            .host("example.com", 8443); // Use port 8443.
            .build();

.. note::

    Internally, the client uses `Armeria`_ as its networking layer. You may want to customize the client
    settings, such as specifying alternative Armeria ``ClientFactory`` or configuring Armeria ``ClientBuilder``.

Specifying an access token
--------------------------
You must specify an access token if your Central Dogma server has enabled authorization.

.. code-block:: java

    CentralDogma dogma = new ArmeriaCentralDogmaBuilder()
            .host("127.0.0.1")
            .accessToken("appToken-cffed349-d573-457f-8f74-4727ad9341ce")
            .build();

.. note::

    See :ref:`auth` for more information about securing a Central Dogma server and managing permissions and
    access tokens.

.. _getting-a-file:

Getting a file
--------------
Once a client is created, you can get a file from a repository:

.. code-block:: java

    import java.util.concurrent.CompletableFuture;
    import com.linecorp.centraldogma.common.Entry;
    import com.linecorp.centraldogma.common.EntryType;
    import com.linecorp.centraldogma.common.Revision;
    import com.linecorp.centraldogma.common.Query;

    CentralDogma dogma = ...;
    CompletableFuture<Entry<String>> future =
            dogma.getFile("myProj", "myRepo", Revision.HEAD, Query.ofText("/a.txt"));

    Entry<String> entry = future.join();
    assert entry.type() == EntryType.TEXT
    assert entry.content() instanceof String; // Text file's content type is String.
    System.err.println(entry.content());

The ``getFile()`` call above will fetch the latest revision of ``/a.txt`` because we specified ``Revision.HEAD``
which is equal to ``new Revision(-1)``. If you want to fetch a specific revision, you can specify the revision
you desire. e.g. ``new Revision(42)`` or ``new Revision(-7)``

.. note::

    Not sure what the meaning of a negative revision number is? Read :ref:`concepts`.

Note that we used ``Query.ofText()``, which tells Central Dogma to fetch the textual content. For a JSON file,
you need to use ``Query.ofJson()``:

.. code-block:: java

    import com.fasterxml.jackson.databind.JsonNode;

    CentralDogma dogma = ...;
    CompletableFuture<Entry<JsonNode>> future =
            dogma.getFile("myProj", "myRepo", Revision.HEAD, Query.ofJson("/b.json"));

Did you notice the return type changed slightly? The type parameter of ``Entry`` is not ``String`` anymore but
``JsonNode`` (from `Jackson <https://github.com/FasterXML/jackson>`_), because we know we are fetching a JSON
file.

Alternatively, you can use ``Query.ofJsonPath()`` to retrieve the result of JSON path evaluation instead of
the whole content, which would be useful especially when you are interested only in a certain part of a
large JSON file:

.. code-block:: java

    CentralDogma dogma = ...;
    CompletableFuture<Entry<JsonNode>> future =
            dogma.getFile("myProj", "myRepo", Revision.HEAD,
                          Query.ofJsonPath("/b.json", "$.someValue"));

Central Dogma server will apply the JSON path expression ``$.someValue`` to the content of ``/b.json``
and return the query result to the client. For example, if ``/b.json`` contains the following:

.. code-block:: json

    { "someValue": 42, "otherValue": "foo" }

You would get:

.. code-block:: json

    42

.. note::

    Central Dogma uses `Jayway's JSON path implementation <https://github.com/json-path/JsonPath>`_.
    Refer to their project page for syntax, example and the list of supported functions.

Getting a merged file
---------------------
You can get a merged file from a repository:

.. code-block:: java

    import com.linecorp.centraldogma.common.MergeQuery;
    import com.linecorp.centraldogma.common.MergeSource;

    CentralDogma dogma = ...;
    List<MergeSource> mergeSources = Arrays.asList(MergeSource.ofRequired("/a.json"),
                                                   MergeSource.ofRequired("/b.json"),
                                                   MergeSource.ofRequired("/c.json"));
    CompletableFuture<MergedEntry<JsonNode>> future =
            dogma.mergeFiles("myProj", "myRepo", Revision.HEAD,
                             MergeQuery.ofJson(mergeSources));

    MergedEntry<JsonNode> mergedEntry = future.join();
    assert mergedEntry.type() == EntryType.JSON
    assert mergedEntry.content() instanceof JsonNode;
    System.err.println(mergedEntry.content());

The ``mergeFiles()`` call above will retrieve the :api:`MergedEntry` which contains a JSON document which
is the result of merging the files specified in the :api:`MergeQuery` sequentially.
We specified ``Revision.HEAD``, so the latest revision of ``/a.json``, ``/b.json`` and ``/c.json``
will be merged. If you want to fetch at the specific revision, you can specify the revision as we
did in :ref:`getting-a-file`.

Only merging JSON files is currently supported. The merge happens traversing children in the JSON object
recursively. In the merge process, the value is simply replaced by the value who has same property name.
Let's consider that the contents of the ``/a.json``, ``/b.json`` and ``/c.json`` are as follows:

``/a.json``

.. code-block:: json

    {
      "someObject": {
        "nullInSomeObject": null
      },
      "someValue": "foo"
    }

``/b.json``

.. code-block:: json

    {
      "someObject": {
        "booleanInSomeObject": true // Add this field because it it not in "/a.json".
      },
      "someValue": "bar" // Replace the value with "bar".
    }

``/c.json``

.. code-block:: json

    {
      "someObject": {
        // Replace the null with 100. null can be converted to any type.
        "nullInSomeObject": 100
      }
    }

Then, the content of the merged entry will be:

.. code-block:: json

    {
      "someObject": {
        "nullInSomeObject": 100,
        "booleanInSomeObject": true
      },
      "someValue": "bar"
    }

.. note::

    Corresponding types of values should be same or one of the types must be ``null`` to replace.
    If their types do not match or neither value is ``null``, you will get a :api:`QueryExecutionException`.

You can mark some files involved in the merge process as optional.

.. code-block:: java

    CentralDogma dogma = ...;
    List<MergeSource> mergeSources = Arrays.asList(MergeSource.ofRequired("/a.json"),
                                                   MergeSource.ofOptional("/b.json"), // <-- It's optional!
                                                   MergeSource.ofRequired("/c.json"));
    CompletableFuture<MergedEntry<JsonNode>> future =
            dogma.mergeFiles("myProj", "myRepo", Revision.HEAD,
                             MergeQuery.ofJson(mergeSources));

Note that we used ``MergeSource.ofOptional("/b.json")``, which tells to include the ``/b.json`` file only if it
exists in the repository. If it does not exist, ``/a.json`` and ``/c.json`` will be merged sequentially.
The files specified as required must exist in the repository. You will get an :api:`EntryNotFoundException`
otherwise.
You will get the :api:`EntryNotFoundException` as well when you specify all of the files as optional
and none of them exists.

As we used ``Query.ofJsonPath()`` in :ref:`getting-a-file`, you can use ``MergeQuery.ofJsonPath()`` to
retrieve the result of JSON path evaluation of the :api:`MergedEntry`.

.. code-block:: java

    CentralDogma dogma = ...;
    List<MergeSource> mergeSources = Arrays.asList(MergeSource.ofRequired("/a.json"),
                                                   MergeSource.ofOptional("/b.json"),
                                                   MergeSource.ofRequired("/c.json"));
    CompletableFuture<MergedEntry<JsonNode>> future =
            dogma.mergeFiles("myProj", "myRepo", Revision.HEAD,
                             MergeQuery.ofJsonPath(mergeSources, "$.someValue"));

Central Dogma server will apply the JSON path expression ``$.someValue`` to the content of the
:api:`MergedEntry`, and return the query result to the client.

Pushing a commit
----------------
You can also push a commit into a repository programmatically:

.. code-block:: java

    import com.linecorp.centraldogma.common.Change;
    import com.linecorp.centraldogma.common.Commit;

    CentralDogma dogma = ...;
    CompletableFuture<Commit> future =
            dogma.push("myProj", "myRepo", Revision.HEAD,
                       "Add /c.json and remove /b.json",
                       Change.ofUpsert("/c.json", "{ \"foo\": \"bar\" }"),
                       Change.ofRemoval("/b.json"));

    Commit commit = future.join();
    System.err.printf("Pushed a commit %s at %s%n",
                      commit.revision(), commit.whenAsText());

In this example, we pushed a commit that contains two changes: one that adds ``/c.json`` and the other that
removes ``/b.json``.

Note that we specified ``Revision.HEAD`` as the base revision. It means this commit is against the latest
commit in the repository ``myRepo``. Alternatively, you can specify an absolute revision so that you are
absolutely sure that nobody pushed a commit while you prepare yours: (pun intended 😉)

.. code-block:: java

    import java.util.concurrent.CompletionException;

    CentralDogma dogma = ...;
    CompletableFuture<Commit> future = dogma.push(..., new Revision(3), ...);
    try {
        future.join();
    } catch (CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ChangeConflictException) {
            // Somebody pushed a commit newer than revision 3 or
            // our changes cannot be applied to the revision 3 cleanly.
        }
    }

Watching a file
---------------
Some configuration properties are dynamic. They are changed often and they must be applied without restarting
the process. The client library provides an easy way to watch a file:

.. code-block:: java

    import com.linecorp.centraldogma.client.Latest;
    import com.linecorp.centraldogma.client.Watcher;

    CentralDogma dogma = ...;
    Watcher<JsonNode> watcher =
            dogma.fileWatcher("myProj", "myRepo",
                              Query.ofJsonPath("/some_file.json", "$.foo"));
    // Register a callback for changes.
    watcher.watch((revision, value) -> {
        System.err.printf("Updated to %s at %s%n", value, revision);
    });

    // Alternatively, without using a callback:
    Latest<JsonNode> latest = watcher.awaitInitialValue(); // Wait for the initial value.
    System.err.printf("Initial: %s at %s%n", latest.value(), latest.revision());

You would want to register a callback to the ``Watcher`` or check the return value of ``Watcher.latest()``
periodically to apply the new settings to your application.

Preparing for unavailability
^^^^^^^^^^^^^^^^^^^^^^^^^^^^
It is possible that the servers are not available when you are waiting for the initial value. To prevent from
your application from awaiting indefinitely, you can specify a timeout and a default value when calling
the ``awaitInitialValue()`` method:

.. code-block:: java

    import static java.util.concurrent.TimeUnit.SECONDS;

    CentralDogma dogma = ...;
    Watcher<JsonNode> watcher = dogma.fileWatcher(..., Query.ofJsonPath(...));
    JsonNode initialValue = watcher.awaitInitialValue(20, SECONDS, someDefaultValue);

    // If you are interested in the Revision of the initial value, you can:
    JsonNode initialValue;
    try {
        Latest<JsonNode> latest = watcher.awaitInitialValue(20, SECONDS);
        System.err.printf("Initial: %s at %s%n", latest.value(), latest.revision());
        initialValue = latest.value();
    } catch (TimeoutException e) {
        System.err.printf("Default: %s%n", someDefaultValue);
        initialValue = someDefaultValue;
    }

Note that a timeout is basically a trade-off. If you specify a smaller timeout, you will have a higher chance
of getting a ``TimeoutException`` or falling back to the default value when the server does not respond in time.
If you specify a larger timeout, you will have a better chance of successful retrieval. It is generally
recommended to use a value not less than 20 seconds so that the client can retry at least a few times before
timing out. Consider specifying a sensible default value if you need to use a small timeout or want to make
sure your application is not affected when the servers have an issue.

Alternatively, you can choose not to use ``awaitInitialValue()`` at all if the value being retrieved is not
part of a critical path, e.g. span collection rate in distributed tracing. In such a case, you can simply
add a callback to :api:`Watcher` or poll the most recently retrieved value using the ``latestValue()`` method:

.. code-block:: java

    CentralDogma dogma = ...;
    Watcher<JsonNode> watcher = dogma.fileWatcher(..., Query.ofJsonPath(...));

    // Using a callback:
    watcher.watch((revision, value) -> {
        System.err.printf("Updated to %s at %s%n", value, revision);
    });

    // Polling the latest value. The client will keep updating in the background.
    JsonNode maybeLatestValue = watcher.latestValue(someDefaultValue);

Specifying multiple hosts
-------------------------
You can also specify more than one host using the ``host()`` method:

.. code-block:: java

    import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;

    ArmeriaCentralDogmaBuilder builder = new ArmeriaCentralDogmaBuilder();
    // The default port 36462 is used if unspecified.
    builder.host("replica1.example.com");
    // You can specify an alternative port number.
    builder.host("replica2.example.com", 1234);
    CentralDogma dogma = builder.build();

.. _using_client_profiles:

Using client profiles
---------------------
You can load the list of the Central Dogma servers from one of the following JSON files in the class path using
``ArmeriaCentralDogmaBuilder.profile(String...)``:

- ``centraldogma-profiles-test.json``
- ``centraldogma-profiles.json`` (if ``centraldogma-profiles-test.json`` is missing)

.. code-block:: java

    ArmeriaCentralDogmaBuilder builder = new ArmeriaCentralDogmaBuilder();
    // Loads the profile 'beta' from:
    // - /centraldogma-profiles-test.json or
    // - /centraldogma-profiles.json
    builder.profile("beta");
    CentralDogma dogma = builder.build();

The following example ``centraldogma-profiles.json`` contains two profiles, ``beta`` and ``release``, and
they contain two replicas, ``replica{1,2}.beta.example.com`` and ``replica{1,2}.release.example.com``
respectively. The replicas in the ``release`` profile support both ``http`` and ``https`` whereas
the replicas in the ``beta`` profile support ``http`` only:

.. code-block:: json

    [ {
      "name": "beta",
      "priority": 0,
      "hosts": [ {
        "host": "replica1.beta.example.com",
        "protocol": "http",
        "port": 36462
      }, {
        "host": "replica2.beta.example.com",
        "protocol": "http",
        "port": 36462
      } ]
    }, {
      "name": "release",
      "priority": 0,
      "hosts": [ {
        "host": "replica1.release.example.com",
        "protocol": "http",
        "port": 36462
      }, {
        "host": "replica1.release.example.com",
        "protocol": "https",
        "port": 8443
      }, {
        "host": "replica2.release.example.com",
        "protocol": "http",
        "port": 36462
      }, {
        "host": "replica2.release.example.com",
        "protocol": "https",
        "port": 8443
      } ]
    } ]

.. tip::

    Use `the JSON schema <_static/schema-centraldogma-profiles.json>`_ to validate your
    ``centraldogma-profiles.json`` file.

You may want to archive this file into a JAR file and distribute it as the *official* client profiles via
a Maven repository, so that your users get the up-to-date host list easily. For example, a user could put
``centraldogma-profiles-1.0.jar`` into his or her class path:

.. code-block:: shell

    $ cat centraldogma-profiles.json
    [ { "name": "beta",    "priority": 0, "hosts": [ ... ] },
      { "name": "release", "priority": 0, "hosts": [ ... ] } ]
    $ jar cvf centraldogma-profiles-1.0.jar centraldogma-profiles.json
    added manifest
    adding: centraldogma-profiles.json

Custom client profiles
^^^^^^^^^^^^^^^^^^^^^^
A user can add his or her own custom client profiles other than the official ones by adding more
``centraldogma-profiles.json`` files to the class path. The following example adds a custom profile called
``localtest``:

.. code-block:: json

    [ {
      "name": "localtest",
      "hosts": [ {
        "host": "127.0.0.1",
        "protocol": "http",
        "port": 36462
      } ]
    } ]

A user can also override the official profile provided by an administrator by specifying a higher priority.
For example, you can override the ``beta`` profile using priority ``100`` which is higher than the default
priority of ``0``:

.. code-block:: json

    [ {
      "name": "beta",
      "priority": 100,
      "hosts": [ {
        "host": "replica1.alternative-beta.example.com",
        "protocol": "http",
        "port": 36462
      }, {
        "host": "replica2.alternative-beta.example.com",
        "protocol": "http",
        "port": 36462
      } ]
    } ]

Note that other profiles such as ``release`` are still loaded from the ``centraldogma-profiles.json`` distributed by
the administrator.

Using DNS-based lookup
----------------------
Central Dogma Java client always retrieves all the IP addresses of a host from the current system DNS server or
the ``/etc/host`` file. Instead of specifying all the individual replica addresses in a client profile,
consider specifying a single host name that's very unlikely to change in the client profile and add multiple
``A`` or ``AAAA`` DNS records to the host name:

.. code-block:: shell

    $ cat centraldogma-profiles.json
    [ {
      "name": "release",
      "hosts": [ {
        "host": "all.dogma.example.com",
        "protocol": "http",
        "port": 36462
      } ]
    } ]
    $ dig all.dogma.example.com
    ; <<>> DiG 9.12.1-P2 <<>> all.dogma.example.com
    ;; global options: +cmd
    ;; Got answer:
    ;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 58779
    ;; flags: qr rd ra; QUERY: 1, ANSWER: 3, AUTHORITY: 0, ADDITIONAL: 1

    ;; OPT PSEUDOSECTION:
    ; EDNS: version: 0, flags:; udp: 1440
    ;; QUESTION SECTION:
    ;all.dogma.example.com. IN A

    ;; ANSWER SECTION:
    all.dogma.example.com. 300 IN A 192.168.1.1
    all.dogma.example.com. 300 IN A 192.168.1.2
    all.dogma.example.com. 300 IN A 192.168.1.3

    ;; Query time: 54 msec

The client will periodically send DNS queries respecting the TTL values advertised by the DNS server and update
the endpoint list dynamically, so that an administrator can add or remove a replica without distributing a new
client profile JAR again.

Spring Boot integration
-----------------------
If you are using `Spring Framework <https://spring.io/>`_, you can inject :api:`com.linecorp.centraldogma.client.CentralDogma`
client very easily. First, add ``centraldogma-client-spring-boot-starter`` into your dependencies.

Gradle:

.. parsed-literal::
    :class: highlight-gradle

    dependencies {
        compile 'com.linecorp.centraldogma:centraldogma-client-spring-boot-starter:\ |release|\ '
    }

Maven:

.. parsed-literal::
    :class: highlight-xml

    <dependencies>
      <dependency>
        <groupId>com.linecorp.centraldogma</groupId>
        <artifactId>centraldogma-client-spring-boot-starter</artifactId>
        <version>\ |release|\ </version>
      </dependency>
    </dependencies>

Then, add a new section called ``centraldogma`` to your Spring Boot application configuration, which is often
named ``application.yml``:

.. code-block:: yaml

    centraldogma:
      hosts:
      - replica1.example.com:36462
      - replica2.example.com:36462
      - replica3.example.com:36462
      access-token: appToken-cffed349-d573-457f-8f74-4727ad9341ce

If you prefer using client profiles as described in :ref:`using_client_profiles`, use the ``profile`` property:

.. code-block:: yaml

    centraldogma:
      profile: beta
      access-token: appToken-cffed349-d573-457f-8f74-4727ad9341ce

If neither ``hosts`` nor ``profile`` property is specified, currently active
`Spring Boot profile <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html>`_
will be used as the client profile. When more than one Spring Boot profile are active, the last matching one
will be chosen.

.. note::

    Do not confuse 'Central Dogma client profile' with 'Spring Boot profile'.

You can also enable a TLS connection or override the default health check request interval:

.. code-block:: yaml

    centraldogma:
      profile: staging
      access-token: appToken-cffed349-d573-457f-8f74-4727ad9341ce
      use-tls: true
      health-check-interval-millis: 15000

Once configured correctly, a new :api:`com.linecorp.centraldogma.client.CentralDogma` client will be created and
injected into your application like the following:

.. code-block:: java

    import org.springframework.boot.CommandLineRunner;
    import org.springframework.boot.SpringApplication;
    import org.springframework.boot.autoconfigure.SpringBootApplication;
    import org.springframework.context.annotation.Bean;

    import com.linecorp.centraldogma.client.CentralDogma;

    @SpringBootApplication
    public class MyApp {

        public static void main(String[] args) {
            SpringApplication.run(MyApp.class, args);
        }

        // CentralDogma is injected automatically by CentralDogmaConfiguration.
        @Bean
        public CommandLineRunner commandLineRunner(CentralDogma dogma) {
            return args -> {
                System.err.println(dogma.listProjects().join());
            };
        }
    }

Read the Javadoc
----------------
Refer to `the API documentation of 'CentralDogma' interface <apidocs/com/linecorp/centraldogma/client/CentralDogma.html>`_
for the complete list of operations you can perform with a Central Dogma server, which should be definitely
much more than what this tutorial covers, such as fetching and watching multiple files.
