# FDBus

Upstream: https://github.com/jeremyczhen/fdbus
Revision: 26771179e4b853e07355e6d4e57de917ca62d2b6
Copyright (C) 2015 Jeremy Chen and upstream contributors. Apache License 2.0; source copyright and license headers preserved.
This package includes source portions needed to build the read-only client. No upstream source modifications.

Client code uses discovery, connect, subscribe/unsubscribe and framework GET_EVENT only. It does not invoke vehicle business methods, publish events, set properties or modify the instrument configuration.
