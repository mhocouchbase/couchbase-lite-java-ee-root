//
//  c4LocalReplicator.hh
//  LiteCore
//
//  Created by Jens Alfke on 9/16/19.
//  Copyright © 2019 Couchbase. All rights reserved.
//

#pragma once
#include "c4Replicator.hh"
#include "LoopbackProvider.hh"

using namespace litecore::websocket;

namespace c4Internal {

    // Asserts that this repo (couchbase-lite-core-EE) is in sync with couchbase-lite-core.
    // If not, you need to fix the submodule references in the common ancestor repo,
    // such as couchbase-lite-ios-ee.
    static_assert(C4Replicator::API_VERSION == 2,
                  "HEAD of couchbase-lite-core-EE is out of sync with couchbase-lite-core");

    /** A replicator with another open C4Database, via LoopbackWebSocket. */
    class C4LocalReplicator : public C4Replicator {
    public:
        C4LocalReplicator(C4Database* db NONNULL,
                          const C4ReplicatorParameters &params,
                          C4Database* otherDB NONNULL)
        :C4Replicator(db, params)
        ,_otherDatabase(otherDB)
        {
            _options.setNoDeltas();
        }


        virtual ~C4LocalReplicator() {
            if (_otherReplicator)
                _otherReplicator->terminate();
        }


        virtual alloc_slice URL() const override {
            return Address(_otherDatabase).url();
        }


        virtual void createReplicator() override {
            auto socket1 = retained(new LoopbackWebSocket(Address(_otherDatabase), Role::Client));
            auto socket2 = retained(new LoopbackWebSocket(Address(_database), Role::Server));
            LoopbackWebSocket::bind(socket1, socket2);

            _replicator = new Replicator(_database, socket1, *this, _options);
            _otherReplicator = new Replicator(_otherDatabase, socket2, *this,
                                              Replicator::Options(kC4Passive, kC4Passive)
                                                  .setNoIncomingConflicts().setNoDeltas());
        }


        void _start() override {
            C4Replicator::_start();
            _selfRetainToo = this;
            _otherReplicator->start();
        }


        virtual void replicatorStatusChanged(Replicator *repl,
                                             const Replicator::Status &newStatus) override
        {
            LOCK(_mutex);
            if (repl == _otherReplicator) {
                // Clean up when _otherReplicator stops:
                if (repl == _otherReplicator && newStatus.level == kC4Stopped) {
                    _otherReplicator->terminate();
                    _otherReplicator = nullptr;
                    _selfRetainToo = nullptr; // balances retain in `start`; may self-destruct!
                }
            } else {
                UNLOCK();
                C4Replicator::replicatorStatusChanged(repl, newStatus);
            }
        }

    private:
        Retained<C4Database> const  _otherDatabase;
        Retained<Replicator>        _otherReplicator;
        Retained<C4LocalReplicator> _selfRetainToo;
    };

}
