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

    ...
    dependencies {
        ...
        compile 'com.linecorp.centraldogma:centraldogma-client:\ |release|\ '
        ...
    }
    ...

Maven:

.. parsed-literal::

    ...
    <dependencies>
      ...
      <dependency>
        <groupId>com.linecorp.centraldogma</groupId>
        <artifactId>centraldogma-client</artifactId>
        <version>\ |release|\ </version>
      </dependency>
      ...
    </dependencies>
    ...

Creating a client
-----------------
First, we should create a new instance of ``CentralDogma``:

.. code-block:: java

    import com.linecorp.centraldogma.client.CentralDogma;

    // The default port 36462 is used if unspecified.
    CentralDogma dogma = CentralDogma.forHost("127.0.0.1");
    // You can specify an alternative port as well:
    CentralDogma dogma2 = CentralDogma.forHost("example.com", 8888);

.. note::

    Internally, the client uses `Armeria`_ as its networking layer. You may want to use ``CentralDogmaBuilder``
    to customize the client settings, such as specifying alternative Armeria ``ClientFactory`` or configuring
    Armeria ``ClientBuilder``.

Getting a file
--------------
Once a client is created, you can get a file from a repository:

.. code-block:: java

    import java.util.concurrent.CompletableFuture;
    import com.linecorp.centraldogma.common.Entry;
    import com.linecorp.centraldogma.common.EntryType;
    import com.linecorp.centraldogma.common.Revision;
    import com.linecorp.centraldogma.common.Query;

    CompletableFuture<Entry<Object>> future =
            dogma.getFile("myProj", "myRepo", Revision.HEAD, Query.identity("/a.txt"));

    Entry<Object> entry = future.join();
    assert entry.type() == EntryType.TEXT
    assert entry.content() instanceof String; // Text file's content type is String.
    System.err.println(entry.content());

The ``getFile()`` call above will fetch the latest revision of ``/a.txt`` because we specified ``Revision.HEAD``
which is equal to ``new Revision(-1)``. If you want to fetch a specific revision, you can specify the revision
you desire. e.g. ``new Revision(42)`` or ``new Revision(-7)``

.. note::

    Not sure what the meaning of a negative revision number is? Read :ref:`concepts`.

Note that we used ``Query.identity()``, which tells Central Dogma to fetch the content in verbatim.
Alternatively, if the file is JSON, you can use ``Query.ofJsonPath()``:

.. code-block:: java

    import com.fasterxml.jackson.databind.JsonNode;

    CompletableFuture<Entry<JsonNode>> future =
            dogma.getFile("myProj", "myRepo", Revision.HEAD,
                          Query.ofJsonPath("/b.json", "$.someValue"));

Did you notice the return type changed slightly? The type parameter of ``Entry`` is not ``Object`` anymore but
``JsonNode`` (from `Jackson <https://github.com/FasterXML/jackson>`_), because we know we are fetching a JSON
file. Central Dogma server will apply the JSON path expression ``$.someValue`` to the content of ``/b.json``
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

    import com.linecorp.centraldogma.common.Author;
    import com.linecorp.centraldogma.common.Change;
    import com.linecorp.centraldogma.common.Commit;

    CompletableFuture<Commit> future =
            dogma.push("myProj", "myRepo", Revision.HEAD,
                       new Author("John Doe", "john@doe.com"),
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

    CompletableFuture<Commit> future = dogma.push(..., new Revision(3), ...);
    try {
        future.join();
    } catch (CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof CentralDogmaException) {
            CentralDogmaException cde = (CentralDogmaException) cause;
            if (cde.getErrorCode() == ErrorCode.CHANGE_CONFLICT) {
                // Somebody pushed a commit newer than revision 3 or
                // our changes cannot be applied to the revision 3 cleanly.
            }
        }
    }

Watching a file
---------------
Some configuration properties are dynamic. They are changed often and they must be applied without restarting
the process. The client library provides an easy way to watch a file:

.. code-block:: java

    import com.linecorp.centraldogma.client.Latest;
    import com.linecorp.centraldogma.client.Watcher;

    Watcher<JsonNode> watcher = dogma.fileWatcher("myProj", "myRepo", Query.ofJsonPath("$.foo"));

    // Register a callback for changes.
    watcher.watch((revision, value) -> {
        System.err.println("Foo has been updated to " + value + " (revision: " + revision + ')');
    });

    // Alternatively, without using a callback:
    Latest<JsonNode> latest = watcher.latest();
    System.err.println("Current foo: " + latest.value() + " (revision: " + latest.revision() + ')');

You would want to register a callback to the ``Watcher`` or check the return value of ``Watcher.latest()``
periodically to apply the new settings to your application.

Specifying multiple hosts
-------------------------
You can use ``CentralDogmaBuilder`` to add more than one host:

.. code-block:: java

    import com.linecorp.centraldogma.client.CentralDogmaBuilder;

    CentralDogmaBuilder builder = new CentralDogmaBuilder();
    // The default port 36462 is used if unspecified.
    builder.host("replica1.example.com");
    // You can specify an alternative port number.
    builder.host("replica2.example.com", 1234);
    CentralDogma dogma = builder.build();

.. _using_client_profiles:

Using client profiles
---------------------
You can load the list of the Central Dogma servers from ``.properties`` resource file in the class path using
``CentralDogma.forProfile()`` or ``CentralDogmaBuilder.profile()``:

.. code-block:: java

    CentralDogmauBuilder builder = new CentralDogmaBuilder();
    // Loads the host list from /centraldogma-profile-beta.properties.
    builder.profile("beta");
    CentralDogma dogma = builder.build();

The resource path of the ``.properties`` file is ``/centraldogma-profile-<profile_name>.properties`` and its
content looks like the following:

.. code-block:: properties

    # The default port 36462 is used if unspecified.
    centraldogma.hosts.0=replica1.beta.example.com
    # You can specify an alternative port number.
    centraldogma.hosts.1=replica2.beta.example.com:1234

You may want to archive this file into a JAR file and distribute it via a Maven repository, so that your users
gets the up-to-date host list easily. For example, a user could put ``centraldogma-profiles-1.0.jar`` into his
or her class path::

    $ cat centraldogma-profile-beta.properties
    centraldogma.host.0=...
    ...
    $ cat centraldogma-profile-staging.properties
    centraldogma.host.0=...
    ...
    $ cat centraldogma-profile-release.properties
    centraldogma.host.0=...
    ...
    $ jar cvf centraldogma-profiles-1.0.jar centraldogma-profile-*.properties
    added manifest
    adding: centraldogma-profile-beta.properties
    adding: centraldogma-profile-staging.properties
    adding: centraldogma-profile-release.properties

Spring Boot integration
-----------------------
If you are using `Spring Framework <https://spring.io/>`_, you can inject ``CentralDogma`` client very easily.

1. Add ``centraldogma-client-spring-boot-autoconfigure`` into your dependencies.
2. Add the client profile to your class path, as described in :ref:`using_client_profiles`.

A new ``CentralDogma`` client will be created and injected using your
`Spring Boot profile <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-profiles.html>`_.
When more than one profile is active, the first matching one will be used. For example,
``/centraldogma-profile-dev.properties`` will be tried first and then ``/centraldogma-profile-hsqldb.properties``
if your active Spring Boot profiles are ``dev`` and ``hsqldb``.

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
Refer to `the API documentation of 'CentralDogma' class <apidocs/index.html?com/linecorp/centraldogma/client/CentralDogma.html>`_
for the complete list of operations you can perform with a Central Dogma server, which should be definitely
much more than what this tutorial covers, such as fetching and watching multiple files.
