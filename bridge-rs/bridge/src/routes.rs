//! Every route besides the bridge-generated core: the closed proxy
//! allowlist and the dedicated, validating handlers.

use axum::extract::Request;

use crate::server::{Handled, State, closed, lookup};

/// The catch-all: a device key first, then the closed allowlist.
pub async fn dispatch_rest(state: &State, request: Request) -> Handled {
    if lookup(state, request.headers()).await?.is_none() {
        return Ok(closed(401, "invalid_credentials"));
    }
    Ok(closed(403, "endpoint_out_of_scope"))
}
