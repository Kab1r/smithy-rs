/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::convert::Infallible;
use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use http_body::Body as HttpBody;
use http_body_util::BodyExt;
use pin_project_lite::pin_project;
use thiserror::Error;
use tower::{Layer, Service, ServiceExt};

use crate::body::{BoxBody, QueryBodyError};
use crate::extension::RuntimeErrorExtension;
use crate::response::IntoResponse;
use crate::routing::tiny_map::TinyMap;
use crate::routing::{method_disallowed, Route, Router, UNKNOWN_OPERATION_EXCEPTION};

use super::AwsQuery;

#[derive(Debug, Error)]
pub enum Error {
    #[error("relative URI is not \"/\"")]
    NotRootUrl,
    #[error("method not POST")]
    MethodNotAllowed,
    #[error("missing Action field in query request body")]
    MissingAction,
    #[error("invalid query request body: {0}")]
    InvalidBody(String),
    #[error("operation not found")]
    NotFound,
    #[error("error converting non-streaming body to bytes: {0}")]
    Body(crate::Error),
}

const ROUTE_CUTOFF: usize = 15;

#[derive(Debug, Clone)]
pub struct AwsQueryRouter<S> {
    routes: TinyMap<&'static str, S, ROUTE_CUTOFF>,
}

impl<S> AwsQueryRouter<S> {
    pub fn layer<L>(self, layer: L) -> AwsQueryRouter<L::Service>
    where
        L: Layer<S>,
    {
        AwsQueryRouter {
            routes: self.routes.into_iter().map(|(key, route)| (key, layer.layer(route))).collect(),
        }
    }

    pub fn boxed<B>(self) -> AwsQueryRouter<Route<QueryBody<B>>>
    where
        S: Service<http::Request<QueryBody<B>>, Response = http::Response<BoxBody>, Error = Infallible>,
        S: Send + Clone + 'static,
        S::Future: Send + 'static,
    {
        AwsQueryRouter {
            routes: self.routes.into_iter().map(|(key, s)| (key, Route::new(s))).collect(),
        }
    }
}

impl<B, S> Router<B> for AwsQueryRouter<S>
where
    S: Clone,
{
    type Service = AwsQueryRoute<S>;
    type Error = Error;

    fn match_route(&self, request: &http::Request<B>) -> Result<Self::Service, Self::Error> {
        if request.uri().path() != "/" {
            return Err(Error::NotRootUrl);
        }
        if request.method() != http::Method::POST {
            return Err(Error::MethodNotAllowed);
        }
        Ok(AwsQueryRoute {
            routes: self.routes.clone(),
        })
    }
}

impl<S> FromIterator<(&'static str, S)> for AwsQueryRouter<S> {
    fn from_iter<T: IntoIterator<Item = (&'static str, S)>>(iter: T) -> Self {
        Self {
            routes: iter.into_iter().collect(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct AwsQueryRoute<S> {
    routes: TinyMap<&'static str, S, ROUTE_CUTOFF>,
}

impl<B, S> Service<http::Request<B>> for AwsQueryRoute<S>
where
    B: HttpBody<Data = Bytes> + Send + 'static,
    B::Error: Into<crate::error::BoxError>,
    S: Service<http::Request<QueryBody<B>>, Response = http::Response<BoxBody>, Error = Infallible>,
    S: Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = http::Response<BoxBody>;
    type Error = Infallible;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<B>) -> Self::Future {
        let routes = self.routes.clone();
        Box::pin(async move {
            let (parts, body) = request.into_parts();
            let bytes = match body.collect().await {
                Ok(collected) => collected.to_bytes(),
                Err(err) => return Ok(Error::Body(crate::Error::new(err)).into_response()),
            };
            let action = match action(&bytes) {
                Ok(action) => action,
                Err(err) => return Ok(err.into_response()),
            };
            let route = match routes.get(action.as_str()) {
                Some(route) => route.clone(),
                None => return Ok(Error::NotFound.into_response()),
            };
            let request = http::Request::from_parts(parts, QueryBody::replayed(bytes));
            route.oneshot(request).await
        })
    }
}

pin_project! {
    pub struct QueryBody<B> {
        #[pin]
        inner: QueryBodyInner<B>,
    }
}

pin_project! {
    #[project = QueryBodyInnerProj]
    enum QueryBodyInner<B> {
        Original {
            #[pin]
            inner: B,
        },
        Replayed {
            #[pin]
            inner: http_body_util::Full<Bytes>,
        },
    }
}

impl<B> QueryBody<B> {
    pub fn replayed(bytes: Bytes) -> Self {
        Self {
            inner: QueryBodyInner::Replayed {
                inner: http_body_util::Full::new(bytes),
            },
        }
    }

    #[allow(dead_code)]
    pub fn original(body: B) -> Self {
        Self {
            inner: QueryBodyInner::Original { inner: body },
        }
    }
}

impl<B> HttpBody for QueryBody<B>
where
    B: HttpBody<Data = Bytes>,
    B::Error: Into<crate::error::BoxError>,
{
    type Data = Bytes;
    type Error = QueryBodyError;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<http_body::Frame<Self::Data>, Self::Error>>> {
        match self.project().inner.project() {
            QueryBodyInnerProj::Original { inner } => inner
                .poll_frame(cx)
                .map_err(|err| QueryBodyError(crate::Error::new(err))),
            QueryBodyInnerProj::Replayed { inner } => inner
                .poll_frame(cx)
                .map_err(|err| match err {}),
        }
    }

    fn is_end_stream(&self) -> bool {
        match &self.inner {
            QueryBodyInner::Original { inner } => inner.is_end_stream(),
            QueryBodyInner::Replayed { inner } => inner.is_end_stream(),
        }
    }

    fn size_hint(&self) -> http_body::SizeHint {
        match &self.inner {
            QueryBodyInner::Original { inner } => inner.size_hint(),
            QueryBodyInner::Replayed { inner } => inner.size_hint(),
        }
    }
}

fn action(bytes: &[u8]) -> Result<String, Error> {
    serde_urlencoded::from_bytes::<Vec<(String, String)>>(bytes)
        .map_err(|err| Error::InvalidBody(err.to_string()))?
        .into_iter()
        .find_map(|(key, value)| (key == "Action").then_some(value))
        .ok_or(Error::MissingAction)
}

impl IntoResponse<AwsQuery> for Error {
    fn into_response(self) -> http::Response<BoxBody> {
        match self {
            Error::MethodNotAllowed => method_disallowed(),
            _ => http::Response::builder()
                .status(http::StatusCode::NOT_FOUND)
                .header(http::header::CONTENT_TYPE, "text/xml")
                .extension(RuntimeErrorExtension::new(UNKNOWN_OPERATION_EXCEPTION.to_string()))
                .body(crate::body::to_boxed(""))
                .expect("invalid HTTP response for AWS Query routing error; please file a bug report under https://github.com/smithy-lang/smithy-rs/issues"),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::convert::Infallible;

    use bytes::Bytes;
    use http::{Method, Request, StatusCode};
    use http_body_util::Full;
    use pretty_assertions::assert_eq;
    use tower::Service;

    use super::*;
    use crate::body::BoxBody;
    use crate::routing::Router;

    /// Build a router with two well-known operations.
    fn make_router() -> AwsQueryRouter<tower::util::ServiceFn<fn(Request<QueryBody<Full<Bytes>>>) -> std::future::Ready<Result<http::Response<BoxBody>, Infallible>>>> {
        fn handler(
            _req: Request<QueryBody<Full<Bytes>>>,
        ) -> std::future::Ready<Result<http::Response<BoxBody>, Infallible>> {
            std::future::ready(Ok(http::Response::builder()
                .status(StatusCode::OK)
                .body(crate::body::to_boxed("ok"))
                .unwrap()))
        }
        AwsQueryRouter::from_iter([
            ("DescribeFoo", tower::service_fn(handler as fn(_) -> _)),
            ("ListBar", tower::service_fn(handler as fn(_) -> _)),
        ])
    }

    /// Build a POST request with a URL-encoded body.
    fn post(body: &'static str) -> Request<Full<Bytes>> {
        Request::builder()
            .method(Method::POST)
            .uri("/")
            .header(http::header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(Full::from(Bytes::from_static(body.as_bytes())))
            .unwrap()
    }

    #[tokio::test]
    async fn routes_known_action() {
        let router = make_router();
        let req = post("Action=DescribeFoo&Version=2010-05-08");
        // match_route validates URI + method; the service call resolves the action.
        let mut route = router.match_route(&req).unwrap();
        let resp = route.call(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn rejects_missing_action() {
        let router = make_router();
        let req = post("Version=2010-05-08");
        let mut route = router.match_route(&req).unwrap();
        let resp = route.call(req).await.unwrap();
        // MissingAction → 404 NOT_FOUND via IntoResponse
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn rejects_unknown_action() {
        let router = make_router();
        let req = post("Action=DoesNotExist&Version=2010-05-08");
        let mut route = router.match_route(&req).unwrap();
        let resp = route.call(req).await.unwrap();
        // Unknown action → 404 NOT_FOUND via IntoResponse
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn rejects_non_post() {
        let router = make_router();
        // Construct a GET request; match_route should reject it immediately.
        let req: Request<Full<Bytes>> = Request::builder()
            .method(Method::GET)
            .uri("/")
            .body(Full::from(Bytes::from_static(b"Action=DescribeFoo")))
            .unwrap();
        let err = router.match_route(&req).unwrap_err();
        assert_eq!(err.to_string(), Error::MethodNotAllowed.to_string());
    }
}
