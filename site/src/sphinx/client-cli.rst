.. _client-cli:

Command-line client
===================
Central Dogma provides a native command-line client ``dogma``, which is located at the ``bin`` directory of
the distribution. You may find it useful to add the ``bin`` directory to your ``PATH`` environment variable
so the client is available regardless of your current working directory.

.. note::

    In this tutorial, we assume your Central Dogma server is running at ``localhost:36462``. Unless specified,
    ``dogma`` will connect to ``localhost:36462`` by default. Use the ``--connect`` option to connect to other
    Central Dogma server. e.g. ``--connect dogma.example.com:36462``

Path syntax
-----------
``dogma`` uses a Unix-like path to refer to a project, a repository, a directory or a file. The first and second
path component signify a project name and a repository name respectively. For example:

- ``foo`` - Project ``foo``
- ``foo/bar`` - Repository ``bar`` in project ``foo``
- ``foo/bar/alice`` - Directory ``/alice`` in repository ``bar`` in project ``foo``
- ``foo/bar/alice/bob.json`` - File ``bob.json`` under the directory ``/alice``

Creating projects and repositories
----------------------------------
Use the ``new`` command to create a project:

.. code-block:: bash

    # Create two projects.
    $ dogma new projFoo
    Created: /projFoo
    $ dogma new projBar
    Created: /projBar

``new`` command is also used for creating a repository:

.. code-block:: bash

    $ dogma new projFoo/repoA
    Created: /projFoo/repoA

Listing entries
---------------
Use the ``ls`` command to list projects, repositories or files.

To list projects, specify no arguments:

.. code-block:: bash

    $ dogma ls
    [
      {
        "name": "projFoo",
        "creator": {
          "name": "System",
          "email": "system@localhost.localdomain"
        },
        "url": "/api/v1/projects/projFoo",
        "createdAt": "2018-03-27T12:36:00Z"
      },
      {
        "name": "projBar",
        "creator": {
          "name": "System",
          "email": "system@localhost.localdomain"
        },
        "url": "/api/v1/projects/projBar",
        "createdAt": "2018-03-26T02:27:26Z"
      }
    ]

To list repositories, specify a project name:

.. code-block:: bash

    $ dogma ls projFoo
    [
      {
        "name": "repoA",
        "creator": {
          "name": "System",
          "email": "system@localhost.localdomain"
        },
        "headRevision": 1,
        "url": "/api/v1/projects/projFoo/repos/repoA",
        "createdAt": "2018-03-27T12:36:00Z"
      },
      ...
    ]

To list files or directories in a repository, specify a project name, a repository name and more.
But before that, let's add a sample file to use under ``samples`` directory:

.. code-block:: bash

    $ echo '{"a":"b"}' > a.json

    $ dogma put projFoo/repoA/samples/a.json a.json -m "Add a.json"
    Put: /projFoo/repoA/samples/a.json

We will learn more about adding and editing files in a repository later in the section :ref:`modifying-repository`.

Then, list the directory:

.. code-block:: bash

    $ dogma ls projFoo/repoA/samples
    [
      {
        "path": "/samples/a.json",
        "type": "JSON",
        "url": "/api/v1/projects/projFoo/repos/repoA/samples/a.json"
      }
    ]

Retrieving a file
-----------------
Use the ``cat`` command to retrieve the content of a file:

.. code-block:: bash

    $ dogma cat projFoo/repoA/samples/a.json
    {
      "a": "b"
    }

You can also query a JSON file using JSON path with a flag ``--jsonpath`` or simply ``-j``:

.. code-block:: bash

    $ dogma cat --jsonpath '$.a' projFoo/repoA/samples/a.json
    "b"

You can use multiple JSON paths as well:

.. code-block:: bash

    $ dogma cat -j '$[?(@.a != "notMyValue")]' -j '$[0].a' projFoo/repoA/samples/a.json
    "b"

Alternatively, you can use the ``get`` command to download the file:

.. code-block:: bash

    $ dogma get projFoo/repoA/samples/a.json
    Downloaded: bar.json

.. _modifying-repository:

Modifying a repository
----------------------
You can add, edit or remove an individual file in a repository using ``put``, ``edit`` and ``rm`` command.

First, let's create a JSON file and add it:

.. code-block:: bash

    $ echo '[1, 2, 3]' > three.json

    $ dogma put projFoo/repoA/numbers/3.json three.json
    Put: /projFoo/repoA/numbers/3.json

The command above uploads ``three.json`` as ``3.json`` under ``/projFoo/repoA/numbers/``.

If you don't specify the file name, the file name will be attached automatically. For example,
if you do ``dogma put projFoo/repoA/numbers/ three.json``, then ``/projFoo/repoA/numbers/three.json`` will be added.

.. note::

    A trailing '/' has important meaning in a ``put`` command. A path ends with a '/' refers to a directory.
    On the other hand, a path that does not end with a '/' refers to a file. For example,
    ``dogma put /projFoo/repoA/a.txt/ b.txt`` will upload ``/projFoo/repoA/a.txt/b.txt``,
    because of the trailing '/' after ``a.txt``.

And then, check it out:

.. code-block:: bash

    $ dogma cat projFoo/repoA/numbers/3.json
    [
      1,
      2,
      3
    ]

.. note::

    When you make a change, you'll be prompted to enter a commit message via a text editor such as ``vim``.
    If you want to simply add a commit message, use the ``--message`` option.

With the ``edit`` command, you can edit a file using a text editor:

.. code-block:: bash

    $ dogma edit projFoo/repoA/numbers/3.json
    ... Text editor shows up ...

Use the ``rm`` command to remove a file:

.. code-block:: bash

    $ dogma rm projFoo/repoA/samples/foo.txt
    Removed: /projFoo/repoA/samples/foo.txt

Specifying a revision
---------------------
Most commands have an option called ``--revision`` which makes the commands retrieve a file at a specific
revision. If not specified, the client assumes ``-1`` which means the latest revision in the repository:

.. code-block:: bash

    $ dogma cat --revision -1 projFoo/repoA/numbers/3.json
    ... Success ...
    $ dogma cat --revision 1 projFoo/repoA/numbers/3.json
    ... Failure, because 3.json does not exist at revision 1 ...

Watching a file
---------------
You can be noticed when a file on the Central Dogma server is changed using the ``watch`` command:

.. code-block:: bash

    $ dogma watch --revision 8 projFoo/repoA/numbers/3.json
    Watcher noticed updated file: projFoo/repoA/numbers/3.json, rev=9
    Content: ...

The ``revision`` option represents that you want to watch the change of the file after the revision.
If you want to keep watching it, use ``--streaming`` option:

.. code-block:: bash

    $ dogma watch --revision 8 --streaming projFoo/repoA/numbers/3.json
    Watcher noticed updated file: projFoo/repoA/numbers/3.json, rev=9
    Content: ...
    Watcher noticed updated file: projFoo/repoA/numbers/3.json, rev=10
    Content: ...
    Watcher noticed updated file: projFoo/repoA/numbers/3.json, rev=11
    Content: ...

    // press CTRL+C
    Received an interrupt, stopping watcher...

Use the ``--help`` option
-------------------------
The ``dogma`` client provides more commands and features than what's demonstrated in this tutorial. ``--help``
option will show the full usage of the client:

.. code-block:: bash

    NAME:
       Central Dogma - Central Dogma client

    USAGE:
       dogma command [arguments]

    COMMANDS:
         ls         Lists the projects, repositories or files
         new        Creates a project or repository
         put        Puts a file to the repository
         edit       Edits a file in the path
         get        Downloads a file in the path
         cat        Prints a file in the path
         rm         Removes a file in the path
         watch      Watches a file in the path
         diff       Gets diff of given path
         log        Shows commit logs of the path
         normalize  Normalizes a revision into an absolute revision
         help, h    Shows a list of commands or help for one command

    GLOBAL OPTIONS:
       --connect value, -c value   Specifies host or IP address with port to connect to:[hostname:port] or [http://hostname:port]
       --username value, -u value  Specifies the username to log in as
       --token value, -t value     Specifies an authorization token to access resources on the server
       --help, -h                  Shows help


Appending the ``--help`` option after a command will print the detailed usage for the command:

.. code-block:: bash

    DESCRIPTION:
       Lists the projects, repositories or files

    USAGE:
       dogma ls [command options] [<project_name>[/<repository_name>[/<path>]]]

    OPTIONS:
       --revision value, -r value  Specifies the revision to operate

