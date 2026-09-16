/* libnfs has public NFS 4.2 renewal, but no public 4.0 client-id getter.
 * Keep this small, pinned-version dependency isolated from the C++ backend. */
#include "config.h"
#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include "../vendor/libnfs/nfs4/libnfs-raw-nfs4.h"
#include "libnfs-private.h"
static void renew_done(struct rpc_context *rpc, int status, void *data, void *opaque) {
    (void)rpc;
    int *failed = opaque;
    if (status == RPC_STATUS_CANCEL) return;
    if (status != RPC_STATUS_SUCCESS || !data || ((COMPOUND4res *)data)->status != NFS4_OK) *failed = 1;
}
int nfssaf_renew_v4(struct nfs_context *nfs, int *failed) {
    nfs_argop4 op = {0};
    COMPOUND4args args = {0};
    op.argop = OP_RENEW;
    op.nfs_argop4_u.oprenew.clientid = nfs->nfsi->clientid;
    args.argarray.argarray_len = 1;
    args.argarray.argarray_val = &op;
    return rpc_nfs4_compound_task(nfs_get_rpc_context(nfs), renew_done, &args, failed) ? 0 : -1;
}
