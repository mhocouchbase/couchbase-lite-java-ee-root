//
//  RESTSyncListener.hh
//
//  Copyright (c) 2017 Couchbase. All rights reserved.
//  COUCHBASE CONFIDENTIAL -- part of Couchbase Lite Enterprise Edition
//

#pragma once
#include "RESTListener.hh"

namespace litecore { namespace REST {
    class RequestResponse;

    
    class RESTSyncListener : public RESTListener {
    public:
        RESTSyncListener(const Config&);

    private:
        virtual void handleSync(RequestResponse&, C4Database*) override;

        bool const _allowPush, _allowPull;
    };

} }
