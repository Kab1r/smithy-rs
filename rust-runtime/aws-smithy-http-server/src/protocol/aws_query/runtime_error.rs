/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use super::rejection::{RequestRejection, ResponseRejection};
use super::AwsQuery;
use crate::extension::RuntimeErrorExtension;
use crate::response::IntoResponse;
use crate::runtime_error::{InternalFailureException, INVALID_HTTP_RESPONSE_FOR_RUNTIME_ERROR_PANIC_MESSAGE};
use http::StatusCode;

#[derive(Debug, thiserror::Error)]
pub enum RuntimeError {
    #[error("request failed to deserialize or response failed to serialize: {0}")]
    Serialization(crate::Error),
    #[error("internal failure: {0}")]
    InternalFailure(crate::Error),
    #[error("not acceptable request: request contains an `Accept` header with a MIME type, and the server cannot return a response body adhering to that MIME type")]
    NotAcceptable,
    #[error("unsupported media type: request does not contain the expected `Content-Type` header value")]
    UnsupportedMediaType,
    #[error("validation failure: operation input contains data that does not adhere to the modeled constraints: {0}")]
    Validation(String),
}

impl RuntimeError {
    pub fn name(&self) -> &'static str {
        match self {
            Self::Serialization(_) => "SerializationException",
            Self::InternalFailure(_) => "InternalFailureException",
            Self::NotAcceptable => "NotAcceptableException",
            Self::UnsupportedMediaType => "UnsupportedMediaTypeException",
            Self::Validation(_) => "ValidationException",
        }
    }

    pub fn status_code(&self) -> StatusCode {
        match self {
            Self::Serialization(_) => StatusCode::BAD_REQUEST,
            Self::InternalFailure(_) => StatusCode::INTERNAL_SERVER_ERROR,
            Self::NotAcceptable => StatusCode::NOT_ACCEPTABLE,
            Self::UnsupportedMediaType => StatusCode::UNSUPPORTED_MEDIA_TYPE,
            Self::Validation(_) => StatusCode::BAD_REQUEST,
        }
    }

    fn message(&self) -> String {
        match self {
            Self::Validation(reason) => reason.clone(),
            other => other.to_string(),
        }
    }
}

impl IntoResponse<AwsQuery> for InternalFailureException {
    fn into_response(self) -> http::Response<crate::body::BoxBody> {
        IntoResponse::<AwsQuery>::into_response(RuntimeError::InternalFailure(crate::Error::new(String::new())))
    }
}

impl IntoResponse<AwsQuery> for RuntimeError {
    fn into_response(self) -> http::Response<crate::body::BoxBody> {
        let name = self.name();
        let body = format!(
            "<ErrorResponse><Error><Type>Sender</Type><Code>{}</Code><Message>{}</Message></Error><RequestId>foo-id</RequestId></ErrorResponse>",
            xml_escape(name),
            xml_escape(&self.message()),
        );
        http::Response::builder()
            .status(self.status_code())
            .header(http::header::CONTENT_TYPE, "text/xml")
            .extension(RuntimeErrorExtension::new(name.to_string()))
            .body(crate::body::to_boxed(body))
            .expect(INVALID_HTTP_RESPONSE_FOR_RUNTIME_ERROR_PANIC_MESSAGE)
    }
}

impl From<ResponseRejection> for RuntimeError {
    fn from(err: ResponseRejection) -> Self {
        Self::Serialization(crate::Error::new(err))
    }
}

impl From<RequestRejection> for RuntimeError {
    fn from(err: RequestRejection) -> Self {
        match err {
            RequestRejection::MissingContentType(_) => Self::UnsupportedMediaType,
            RequestRejection::ConstraintViolation(reason) => Self::Validation(reason),
            RequestRejection::NotAcceptable => Self::NotAcceptable,
            _ => Self::Serialization(crate::Error::new(err)),
        }
    }
}

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}
