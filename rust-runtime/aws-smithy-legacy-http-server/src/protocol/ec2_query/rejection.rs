/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::rejection::MissingContentTypeReason;
use aws_smithy_runtime_api::http::HttpError;
use std::num::TryFromIntError;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ResponseRejection {
    #[error("invalid bound HTTP status code; status codes must be inside the 100-999 range: {0}")]
    InvalidHttpStatusCode(TryFromIntError),
    #[error("error building HTTP response: {0}")]
    Build(#[from] aws_smithy_types::error::operation::BuildError),
    #[error("error serializing XML-encoded body: {0}")]
    Serialization(#[from] aws_smithy_types::error::operation::SerializationError),
    #[error("error building HTTP response: {0}")]
    HttpBuild(#[from] http::Error),
}

#[derive(Debug, Error)]
pub enum RequestRejection {
    #[error("error converting non-streaming body to bytes: {0}")]
    BufferHttpBodyBytes(crate::Error),
    #[error("request contains invalid value for `Accept` header")]
    NotAcceptable,
    #[error("expected `Content-Type` header not found: {0}")]
    MissingContentType(#[from] MissingContentTypeReason),
    #[error("error deserializing request HTTP body as query parameters: {0}")]
    QueryDeserialize(String),
    #[error("error parsing timestamp from request body: {0}")]
    DateTimeParse(#[from] aws_smithy_types::date_time::DateTimeParseError),
    #[error("error parsing primitive type from request body: {0}")]
    PrimitiveParse(#[from] aws_smithy_types::primitive::PrimitiveParseError),
    #[error("request does not adhere to modeled constraints: {0}")]
    ConstraintViolation(String),
    #[error("failed to convert request: {0}")]
    HttpConversion(#[from] HttpError),
}

impl From<std::convert::Infallible> for RequestRejection {
    fn from(err: std::convert::Infallible) -> Self {
        match err {}
    }
}

impl From<crate::Error> for RequestRejection {
    fn from(err: crate::Error) -> Self {
        Self::BufferHttpBodyBytes(err)
    }
}

impl From<crate::body::QueryBodyError> for RequestRejection {
    fn from(err: crate::body::QueryBodyError) -> Self {
        Self::BufferHttpBodyBytes(crate::Error::new(err))
    }
}

convert_to_request_rejection!(hyper::Error, BufferHttpBodyBytes);
convert_to_request_rejection!(Box<dyn std::error::Error + Send + Sync + 'static>, BufferHttpBodyBytes);
