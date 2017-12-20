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
Use the ``new`` command to create a project::

    # Create two projects.
    $ dogma new projFoo
    Created: /projFoo
    $ dogma new projBar
    Created: /projBar

``new`` command is also used for creating a repository::

    $ dogma new projFoo/repoA
    Created: /projFoo/repoA

Listing entries
---------------
Use the ``ls`` command to list projects, repositories or files.

To list projects, specify no arguments::

    $ dogma ls
    [
        {
            "name": "projBar"
        },
        {
            "name": "projFoo"
        }
    ]

To list repositories, specify a project name::

    $ dogma ls projFoo
    [
        {
            "name": "main",
            "head": {
                "revision": {
                    "major": 2,
                    "minor": 0,
                },
                "timestamp": "2017-09-23T00:48:13Z",
                "summary": "Add the sample files",
                "detail": {
                    "content": "",
                    "markup": "PLAINTEXT"
                }
            }
        },
        ...
    ]

To list files or directories in a directory, specify a project name, a repository name and more::

    $ dogma ls projFoo/main/samples
    [
        {
            "path": "/samples/bar.json",
            "type": "JSON",
            "content": "..."
        },
        {
            "path": "/samples/foo.txt",
            "type": "TEXT",
            "content": "..."
        },
        {
            "path": "/samples/qux.json",
            "type": "JSON",
            "content": "..."
        }
    ]

Retrieving a file
-----------------
Use the ``cat`` command to retrieve the content of a file::

    $ dogma cat projFoo/main/samples/foo.txt
    Lorem ipsum dolor sit amet, consectetur adipiscing elit...

You can also query a JSON file using JSON path::

    $ dogma cat --jsonpath '$.a' projFoo/main/samples/bar.json
    "Pellentesque feugiat, est sit amet condimentum sagittis...

Alternatively, you can use the ``get`` command to download the file::

    $ dogma get projFoo/main/samples/bar.json
    Downloaded: bar.json

Modifying a repository
----------------------
You can add, edit or remove an individual file in a repository using ``put``, ``edit`` and ``rm`` command.

First, let's create a JSON file and add it::

    $ echo '[1, 2, 3, 4, 5, 6, 7, 8, 9]' > nine.json

    $ dogma put projFoo/main/numbers/9.json nine.json
    Put: /projFoo/main/numbers/9.json

The command above uploads ``nine.json`` as ``9.json`` under ``/projFoo/main/numbers/``.

If you don't specify the file name, the file name will be attached automatically. For example,
if you do ``dogma put projFoo/main/numbers/ nine.json``, then ``/projFoo/main/numbers/nine.json`` will be added.

.. note::

    A trailing '/' has important meaning in a ``put`` command. A path ends with a '/' refers to a directory.
    On the other hand, a path that does not end with a '/' refers to a file. For example,
    ``dogma put /projFoo/main/a.txt/ b.txt`` will upload ``/projFoo/main/a.txt/b.txt``,
    because of the trailing '/' after ``a.txt``.

And then, check it out::

    $ dogma ls projFoo/main/numbers
    [
        {
            "path": "/numbers/9.json",
            "type": "JSON",
            "content": "[1,2,3,4,5,6,7,8,9]"
        }
    ]

.. note::

    When you make a change, you'll be prompted to enter a commit message via a text editor such as ``vim``.

With the ``edit`` command, you can edit a file using a text editor::

    $ dogma edit projFoo/main/numbers/9.json
    ... Text editor shows up ...

Use the ``rm`` command to remove a file::

    $ dogma rm projFoo/main/samples/foo.txt
    Removed: /projFoo/main/samples/foo.txt

Specifying a revision
---------------------
Most commands have an option called ``--revision`` which makes the commands retrieve a file at a specific
revision. If not specified, the client assumes ``-1`` which means the latest revision in the repository::

    $ dogma cat --revision -1 projFoo/main/numbers/9.json
    ... Success ...
    $ dogma cat --revision 1 projFoo/main/numbers/9.json
    ... Failure, because 9.json does not exist at revision 1 ...

Use the ``--help`` option
-------------------------
The ``dogma`` client provides more commands and features than what's demonstrated in this tutorial. ``--help``
option will show the full usage of the client::

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
         diff       Gets diff of given path
         log        Shows commit logs of the path
         normalize  Normalizes a revision into an absolute revision
         search     Searches files matched by the term
         help, h    Shows a list of commands or help for one command

    GLOBAL OPTIONS:
       --connect value, -c value   Specifies host or IP address with port to connect to:[hostname:port] or [http://hostname:port]
       --username value, -u value  Specifies the username to log in as
       --token value, -t value     Specifies the token to authenticate
       --help, -h                  Shows help


Appending the ``--help`` option after a command will print the detailed usage for the command::

    DESCRIPTION:
       Lists the projects, repositories or files

    USAGE:
       dogma ls [command options] [<project_name>[/<repository_name>[/<path>]]]

    OPTIONS:
       --revision value, -r value  Specifies the revision to operate

