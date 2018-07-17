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
    :class: highlight-groovy

    ...
    dependencies {
        ...
        compile 'com.linecorp.centraldogma:centraldogma-client-armeria-legacy:\ |release|\ '
        ...
    }
    ...

Maven:

.. parsed-literal::
    :class: highlight-xml

    ...
    <dependencies>
      ...
      <dependency>
        <groupId>com.linecorp.centraldogma</groupId>
        <artifactId>centraldogma-client-armeria-legacy</artifactId>
        <version>\ |release|\ </version>
      </dependency>
      ...
    </dependencies>
    ...

Creating a client
-----------------
First, we should create a new instance of :api:`com.linecorp.centraldogma.client.CentralDogma`:

.. code-block:: java

    import com.linecorp.centraldogma.client.CentralDogma;
    import com.linecorp.centraldogma.client.armeria.legacy.LegacyCentralDogmaBuilder;

    // The default port 36462 is used if unspecified.
    CentralDogma dogma = new LegacyCentralDogmaBuilder()
            .host("127.0.0.1")
            .build();
    // You can specify an alternative port or enable TLS as well:
    CentralDogma dogma2 = new LegacyCentralDogmaBuilder()
            .useTls()                   // Enable TLS.
            .host("example.com", 8443); // Use port 8443.
            .build();

.. note::

    Internally, the client uses `Armeria`_ as its networking layer. You may want to customize the client
    settings, such as specifying alternative Armeria ``ClientFactory`` or configuring Armeria ``ClientBuilder``.

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
    System.err.println("Pushed a commit " + commit.revision() + " at " + commit.whenAsText());

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
    Watcher<JsonNode> watcher = dogma.fileWatcher("myProj", "myRepo",
                                                  Query.ofJsonPath("/some_file.json", "$.foo"));
    // Register a callback for changes.
    watcher.watch((revision, value) -> {
        System.err.println("Foo has been updated to " + value + " (revision: " + revision + ')');
    });

    // Alternatively, without using a callback:
    watcher.awaitInitialValue();                // Wait until the initial value is available.
    Latest<JsonNode> latest = watcher.latest(); // Get the latest value.
    System.err.println("Current foo: " + latest.value() + " (revision: " + latest.revision() + ')');

You would want to register a callback to the ``Watcher`` or check the return value of ``Watcher.latest()``
periodically to apply the new settings to your application.

Specifying multiple hosts
-------------------------
You can also specify more than one host using the ``host()`` method:

.. code-block:: java

    import com.linecorp.centraldogma.client.armeria.legacy.LegacyCentralDogmaBuilder;

    LegacyCentralDogmaBuilder builder = new LegacyCentralDogmaBuilder();
    // The default port 36462 is used if unspecified.
    builder.host("replica1.example.com");
    // You can specify an alternative port number.
    builder.host("replica2.example.com", 1234);
    CentralDogma dogma = builder.build();

.. _using_client_profiles:

Using client profiles
---------------------
You can load the list of the Central Dogma servers from one of the following JSON files in the class path using
``LegacyCentralDogmaBuilder.profile(String...)``:

- ``centraldogma-profile-test.json``
- ``centraldogma-profile.json`` (if ``centraldogma-profile-test.json`` is missing)

.. code-block:: java

    LegacyCentralDogmaBuilder builder = new LegacyCentralDogmaBuilder();
    // Loads the profile 'beta' from /centraldogma-profiles-test.json or /centraldogma-profiles.json
    builder.profile("beta");
    CentralDogma dogma = builder.build();

The following example ``centraldogma-profiles.json`` contains two profiles, ``beta`` and ``release``, and
they contain two replicas, ``replica{1,2}.beta.example.com`` and ``replica{1,2}.release.example.com``
respectively. The replicas in the ``release`` profile support both ``http`` and ``https`` whereas
the replicas in the ``beta`` profile support ``http`` only:

.. code-block:: json

    [ {
      "name": "beta",
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

You may want to archive this file into a JAR file and distribute it via a Maven repository, so that your users
get the up-to-date host list easily. For example, a user could put ``centraldogma-profiles-1.0.jar`` into his
or her class path::

    $ cat centraldogma-profiles.json
    [ { "name": "release", "hosts": [ ... ] } ]

    $ jar cvf centraldogma-profiles-1.0.jar centraldogma-profiles.json
    added manifest
    adding: centraldogma-profiles.json

Using DNS-based lookup
----------------------
Central Dogma Java client always retrieves all the IP addresses of a host from the current system DNS server or
the ``/etc/host`` file. Instead of specifying all the individual replica addresses in a client profile,
consider specifying a single host name that's very unlikely to change in the client profile and add multiple
``A`` or ``AAAA`` DNS records to the host name::

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
client very easily.

1. Add ``centraldogma-client-spring-boot-autoconfigure`` into your dependencies.
2. Add the client profile to your class path, as described in :ref:`using_client_profiles`.

A new :api:`com.linecorp.centraldogma.client.CentralDogma` client will be created and injected using your
`Spring Boot profile <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html>`_.
When more than one profile is active, the last matching one will be used from ``/centraldogma-profiles-test.json``
or ``/centraldogma-profiles.json``.

Once configured correctly, you would be able to run an application like the following:

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
