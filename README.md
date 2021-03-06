easy-update-fs-rdb
==================
[![Build Status](https://travis-ci.org/DANS-KNAW/easy-update-fs-rdb.png?branch=master)](https://travis-ci.org/DANS-KNAW/easy-update-fs-rdb)

Update the EASY File-system RDB with file and folder metadata from Fedora.


SYNOPSIS
--------

    easy-update-fs-rdb [--file|-f <text-file-with-dataset-id-per-line> | --dataset-pids|-d <dataset-pid>]


DESCRIPTION
-----------

The EASY File-system RDB (Relational Database) is a component that stores basis metadata about files and folders in 
the EASY Fedora Commons Repository. Its purpose is to provide quick access to this metadata, which is necessary for
the EASY Web-UI to function properly. ``easy-update-fs-rdb`` extracts the required file and folder metadata from
Fedora and updates the File-system RDB with this metadata. 

When an error occurs during the insert/update of the db-records for a dataset, the database transaction for that dataset is 'rolled back'
and executions stops. Note that in this case any remaining datasets (of the batch) still need to be processed!


ARGUMENTS
---------

     -d, --dataset-pids  <arg>...   ids of datasets for which to update the file and folder metadata in the
                                    File-system RDB
     -f, --file  <arg>              Text file with a dataset-id per line
     -h, --help                     Show help message
     -v, --version                  Show version of this program

    trailing arguments:
     dataset-pids (not required)   ids of datasets for which to update the file and folder metadata in the
                                   File-system RDB


INSTALLATION AND CONFIGURATION
------------------------------

### Installation steps:

1. Unzip the tarball to a directory of your choice, e.g. /opt/
2. A new directory called easy-update-fs-rdb-<version> will be created
3. Create an environment variabele ``EASY_UPDATE_FS_RDB_HOME`` with the directory from step 2 as its value
4. Add ``$EASY_UPDATE_FS_RDB_HOME/bin`` to your ``PATH`` environment variable.

### Configuration

General configuration settings can be set in ``$EASY_UPDATE_FS_RDB_HOME/cfg/application.properties`` and 
logging can be configured in ``$EASY_UPDATE_FS_RDB_HOME/cfg/logback.xml``. The available settings are explained in
comments in aforementioned files.


BUILDING FROM SOURCE
--------------------

Prerequisites:

* Java 8 or higher
* Maven 3.3.3 or higher
 
Steps:

        git clone https://github.com/DANS-KNAW/easy-update-fs-rdb.git
        cd easy-update-fs-rdb
        mvn install




