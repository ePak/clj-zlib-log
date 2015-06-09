# clj-zlib-log

This is an example project of how to read a log file with special encoding and z-compressed using [Gloss](https://github.com/ztellman/gloss), and push entries to LogStash.

This project uses [Wildcard](https://github.com/EsotericSoftware/wildcard) to do globbing.

## Installation

    $ lein uberjar

## Usage

    $ java -jar clj-zlib-log-0.1.0-standalone.jar [options] DIR_1 [DIR_2...]

DIR_1 etc. can be a glob expression.

## Options

    -r --root ROOT_DIR      Root directory to search (default: .)
    -f --file FILE          File pattern (default: *.zlog)
       --host HOST          LogStash hostname (default: localhost)
       --port PORT          LogStash port number (default: 5140)
    -t --time-zone HOURS    The time-zone where this logs are recorded in (default: 0, ie UK)
    -h --help               Show this help information

## Examples

Assuming you have a bunch of logs from different applications under /var/log.

    /var/log/app1/access.zlog
    /var/log/app1/error.zlog
    /var/log/app2/access.zlog
    /var/log/app2/error.zlog


    $ java -jar clj-zlib-log-0.1.0-standalone.jar -r /var/log app* 

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.

