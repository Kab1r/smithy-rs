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
use pin_project_lite::pin_project;
use thiserror::Error;
use tower::{Layer, Service, ServiceExt};

use crate::body::{BoxBody, QueryBodyError};
use crate::extension::RuntimeErrorExtension;
use crate::response::IntoResponse;
use crate::routing::tiny_map::TinyMap;
use crate::routing::{method_disallowed, Route, Router, UNKNOWN_OPERATION_EXCEPTION};

use super::Ec2Query;

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
pub struct Ec2QueryRouter<S> {
    routes: TinyMap<&'static str, S, ROUTE_CUTOFF>,
}

impl<S> Ec2QueryRouter<S> {
    pub fn layer<L>(self, layer: L) -> Ec2QueryRouter<L::Service>
    where
        L: Layer<S>,
    {
        Ec2QueryRouter {
            routes: self.routes.into_iter().map(|(key, route)| (key, layer.layer(route))).collect(),
        }
    }

    pub fn boxed<B>(self) -> Ec2QueryRouter<Route<QueryBody<B>>>
    where
        S: Service<http::Request<QueryBody<B>>, Response = http::Response<BoxBody>, Error = Infallible>,
        S: Send + Clone + 'static,
        S::Future: Send + 'static,
    {
        Ec2QueryRouter {
            routes: self.routes.into_iter().map(|(key, s)| (key, Route::new(s))).collect(),
        }
    }
}

impl<B, S> Router<B> for Ec2QueryRouter<S>
where
    S: Clone,
{
    type Service = Ec2QueryRoute<S>;
    type Error = Error;

    fn match_route(&self, request: &http::Request<B>) -> Result<Self::Service, Self::Error> {
        if request.uri().path() != "/" {
            return Err(Error::NotRootUrl);
        }
        if request.method() != http::Method::POST {
            return Err(Error::MethodNotAllowed);
        }
        Ok(Ec2QueryRoute {
            routes: self.routes.clone(),
        })
    }
}

impl<S> FromIterator<(&'static str, S)> for Ec2QueryRouter<S> {
    fn from_iter<T: IntoIterator<Item = (&'static str, S)>>(iter: T) -> Self {
        Self {
            routes: iter.into_iter().collect(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Ec2QueryRoute<S> {
    routes: TinyMap<&'static str, S, ROUTE_CUTOFF>,
}

impl<B, S> Service<http::Request<B>> for Ec2QueryRoute<S>
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
            let bytes = match hyper::body::to_bytes(body).await {
                Ok(bytes) => bytes,
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
            inner: hyper::Body,
        },
    }
}

impl<B> QueryBody<B> {
    pub fn replayed(bytes: Bytes) -> Self {
        Self {
            inner: QueryBodyInner::Replayed {
                inner: hyper::Body::from(bytes),
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

    fn poll_data(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Result<Self::Data, Self::Error>>> {
        match self.project().inner.project() {
            QueryBodyInnerProj::Original { inner } => inner
                .poll_data(cx)
                .map_err(|err| QueryBodyError(crate::Error::new(err))),
            QueryBodyInnerProj::Replayed { inner } => inner
                .poll_data(cx)
                .map_err(|err| QueryBodyError(crate::Error::new(err))),
        }
    }

    fn poll_trailers(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<Option<http::HeaderMap>, Self::Error>> {
        match self.project().inner.project() {
            QueryBodyInnerProj::Original { inner } => inner
                .poll_trailers(cx)
                .map_err(|err| QueryBodyError(crate::Error::new(err))),
            QueryBodyInnerProj::Replayed { inner } => inner
                .poll_trailers(cx)
                .map_err(|err| QueryBodyError(crate::Error::new(err))),
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

impl IntoResponse<Ec2Query> for Error {
    fn into_response(self) -> http::Response<BoxBody> {
        match self {
            Error::MethodNotAllowed => method_disallowed(),
            _ => http::Response::builder()
                .status(http::StatusCode::NOT_FOUND)
                .header(http::header::CONTENT_TYPE, "text/xml;charset=UTF-8")
                .extension(RuntimeErrorExtension::new(UNKNOWN_OPERATION_EXCEPTION.to_string()))
                .body(crate::body::to_boxed(""))
                .expect("invalid HTTP response for EC2 Query routing error; please file a bug report under https://github.com/smithy-lang/smithy-rs/issues"),
        }
    }
}
