//
//  RESTSyncListener.cc
//
//  Copyright (c) 2017 Couchbase. All rights reserved.
//  COUCHBASE CONFIDENTIAL -- part of Couchbase Lite Enterprise Edition
//

#include "RESTSyncListener.hh"
#include "Request.hh"
#include "BuiltInWebSocket.hh"
#include "BLIPConnection.hh"
#include "Replicator.hh"
#include "c4ListenerInternal.hh"
#include "c4Private.h"
#include "c4ExceptionUtils.hh"
#include "c4.hh"
#include "Logging.hh"

namespace litecore { namespace REST {
    using namespace std;
    using namespace fleece;
    using namespace net;
    using namespace websocket;

    RESTSyncListener::RESTSyncListener(const Config &config)
    :RESTListener(config)
    ,_allowPush(config.allowPush)
    ,_allowPull(config.allowPull)
    {
        if (config.apis & kC4SyncAPI) {
            Assert(_allowPush || _allowPull);
            C4LogToAt(RESTLog, kC4LogInfo, "Replication handler registered, at /*/_blipsync");
        }
    }


    void RESTSyncListener::handleSync(RequestResponse &rq, C4Database *db) {
        if (!rq.isValidWebSocketRequest()) {
            rq.respondWithStatus(HTTPStatus::BadRequest);
            return;
        }

        string protocol = string(blip::Connection::kWSProtocolName) + repl::kReplicatorProtocolName;
        slice protocols = rq["Sec-WebSocket-Protocol"];
        if (!protocols.find(slice(protocol))) {
            rq.respondWithStatus(HTTPStatus::Forbidden);
            return;
        }

        rq.sendWebSocketResponse(protocol);
        string url = "x-incoming-ws://" + rq.peerAddress();
        Retained<WebSocket> webSocket = new BuiltInWebSocket(alloc_slice(url), rq.extractSocket());

        // Now start the replicator, attached to this socket:
        C4Error error;
        C4ReplicatorParameters params = {};
        params.push = (_allowPush ? kC4Passive : kC4Disabled);
        params.pull = (_allowPull ? kC4Passive : kC4Disabled);
        c4::ref<C4Replicator> repl( c4repl_newWithWebSocket(db, webSocket, params, &error) );
        if (!repl) {
            Warn("Couldn't start replicator: %s", c4error_descriptionStr(error));
            return;
        }
        c4repl_start(repl);
    }


    const C4ListenerAPIs kListenerAPIs = kC4RESTAPI | kC4SyncAPI;

    Listener* NewListener(const C4ListenerConfig *config) {
        if ((config->apis & ~kListenerAPIs) != 0)
            return nullptr;
        if (config->apis & kC4SyncAPI)
            return new RESTSyncListener(*config);
        else
            return new RESTListener(*config);
    }

} }
