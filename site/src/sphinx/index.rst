.. _`Apache ZooKeeper`: https://zookeeper.apache.org/
.. _`Java`: http://www.oracle.com/technetwork/java/javase/downloads/
.. _`JSON path`: https://github.com/json-path/JsonPath
.. _`pull request`: https://help.github.com/articles/about-pull-requests/
.. _`the introductory slides`: https://speakerdeck.com/trustin/central-dogma-lines-git-based-highly-available-service-configuration-repository

.. _index:

Welcome to Central Dogma
========================

*Central Dogma* is an open-source highly-available version-controlled service configuration repository based on
Git, ZooKeeper and HTTP/2. With Central Dogma, you can:

- Store your configuration files such as ``.json``, ``.yaml`` and ``.xml`` into a centralized, multi-master
  replicated, version-controlled repository.
- Retrieve your configuration using RESTful API, Java library and command-line client.
- Let your servers get notified immediately when the configuration files are updated and the new settings are
  applied without server restarts.
- Send a pull request for configuration changes and get it reviewed and merged by teammates;
  much less chance of making a bad configuration change!

Want a quick tour?
------------------
Check out `the introductory slides`_:

.. raw:: html

    <div style="max-width: 512px; margin-bottom: 1em"><script async class="speakerdeck-embed" data-id="a7b66c82136e494595cc06507adef5a4" data-ratio="1.77777777777778" src="//speakerdeck.com/assets/embed.js"></script></div>

Give it a try!
--------------
|download| now and give it a try (`Java`_ required):

.. parsed-literal::

    $ tar zxvf centraldogma-\ |release|\ .tgz
    $ cd centraldogma-\ |release|\ /
    $ bin/startup
    # Open http://127.0.0.1:36462/ in your browser for administrative console.

Using Docker? Launch our image at `Docker Hub <https://hub.docker.com/r/line/centraldogma/>`_::

    $ docker run -p 36462:36462 line/centraldogma

Repository service for textual configuration
--------------------------------------------
- Primarily designed for storing JSON

  - Also supports any text formats such as YAML, XML, INI and even JavaScript

- Store your start-time and run-time settings, such as:

  - Application parameters and bean properties
  - User and IP blacklists
  - Scheduled maintenance notice
  - Roll-out and A/B experiment parameters
  - Rule engine scripts

Highly-available
----------------
- Multi-master replicated

  - Using `Apache ZooKeeper`_ as a replication log queue

Version-controlled
------------------
- Keeps full history of configuration changes in Git repositories.
- Can hold a lot more information than in-memory competitors.

Query and notification mechanism
--------------------------------
- Query your JSON files with `JSON path`_.
- Watch your files and get notified immediately when they are modified.

Fine-grained access control
---------------------------
- Pluggable authentication layer
- Role-based per-repository permissions
- See :ref:`auth` for more information.

Automated mirroring from an external Git repository
---------------------------------------------------
- Keep your settings in a GitHub or GitLab repository.
- Send a `pull request`_ to modify the settings and get it reviewed and merged.
- Let Central Dogma mirror your settings so they are:

  - Highly-available
  - Queryable
  - Watchable
  - Access-controlled

Read more
---------
.. toctree::
    :maxdepth: 2

    setup
    concepts
    client-cli
    client-java
    mirroring
    auth
    known-issues

    Release notes <https://github.com/line/centraldogma/releases>
    API documentation <apidocs/index.html#://>
    Source cross-reference <xref/index.html#://>
    Questions and answers <https://github.com/line/centraldogma/issues?q=label%3Aquestion-answered>
    Fork me at GitHub <https://github.com/line/centraldogma>
    Contributing <https://github.com/line/centraldogma/blob/master/CONTRIBUTING.md>
