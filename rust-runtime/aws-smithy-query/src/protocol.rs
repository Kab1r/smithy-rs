/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_runtime_api::client::orchestrator::Metadata;
use aws_smithy_runtime_api::http::{Request, Response};
use aws_smithy_schema::protocol::{apply_http_endpoint, ClientProtocolInner};
use aws_smithy_schema::serde::{
    SerdeError, SerializableStruct, ShapeDeserializer, ShapeSerializer,
};
use aws_smithy_schema::{shape_id, Schema, ShapeId};
use aws_smithy_types::body::SdkBody;
use aws_smithy_types::config_bag::ConfigBag;
use aws_smithy_types::{BigDecimal, BigInteger, Blob, DateTime, Document};

use crate::codec::serializer::QueryShapeSerializer;

#[derive(Debug)]
pub struct AwsQueryProtocol {
    protocol_id: ShapeId,
    service_version: String,
}

impl AwsQueryProtocol {
    pub fn new(version: impl Into<String>) -> Self {
        Self {
            protocol_id: shape_id!("aws.protocols", "awsQuery"),
            service_version: version.into(),
        }
    }
}

impl ClientProtocolInner for AwsQueryProtocol {
    type Request = Request;
    type Response = Response;

    fn protocol_id(&self) -> &ShapeId {
        &self.protocol_id
    }

    fn serialize_request(
        &self,
        input: &dyn SerializableStruct,
        input_schema: &Schema,
        endpoint: &str,
        cfg: &ConfigBag,
    ) -> Result<Request, SerdeError> {
        let op_name = cfg
            .load::<Metadata>()
            .map(|m| m.name().to_string())
            .unwrap_or_default();

        let mut serializer = QueryShapeSerializer::new(&op_name, &self.service_version);
        serializer.write_struct(input_schema, input)?;
        let body = aws_smithy_schema::codec::FinishSerializer::finish(serializer);

        let uri = if endpoint.is_empty() { "/" } else { endpoint };
        let mut request = Request::new(SdkBody::from(body));
        request
            .set_method("POST")
            .map_err(|e| SerdeError::custom(format!("{e}")))?;
        request
            .set_uri(uri)
            .map_err(|e| SerdeError::custom(format!("{e}")))?;
        request
            .headers_mut()
            .insert("Content-Type", "application/x-www-form-urlencoded");
        Ok(request)
    }

    fn deserialize_response<'a>(
        &self,
        response: &'a Response,
        _output_schema: &Schema,
        _cfg: &ConfigBag,
    ) -> Result<Box<dyn ShapeDeserializer + 'a>, SerdeError> {
        let body = response
            .body()
            .bytes()
            .ok_or_else(|| SerdeError::custom("response body not available"))?;
        // awsQuery responses are wrapped: <OpResponse><OpResult>DATA</OpResult></OpResponse>
        // Strip the two-level envelope to get to the actual data.
        let body_str = std::str::from_utf8(body).map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })?;
        let inner = strip_aws_query_envelope(body_str)?;
        Ok(Box::new(OwnedXmlDeserializer { data: inner }))
    }

    fn update_endpoint(
        &self,
        request: &mut Request,
        endpoint: &aws_smithy_types::endpoint::Endpoint,
        cfg: &ConfigBag,
    ) -> Result<(), SerdeError> {
        apply_http_endpoint(request, endpoint, cfg)
    }
}

fn strip_aws_query_envelope(xml: &str) -> Result<String, SerdeError> {
    use aws_smithy_xml::decode::Document as XmlDoc;
    let mut doc = XmlDoc::new(xml);
    let mut response_el = doc.root_element().map_err(|e| SerdeError::InvalidInput {
        message: format!("invalid XML response: {e}"),
    })?;
    // First child is <OpResult>
    let mut result_el = response_el
        .next_tag()
        .ok_or_else(|| SerdeError::InvalidInput {
            message: "missing result element in awsQuery response".into(),
        })?;
    // Collect the inner XML content as a wrapped element for the deserializer
    let mut inner = String::new();
    while let Some(mut child) = result_el.next_tag() {
        let name = child.start_el().local().to_string();
        let text =
            aws_smithy_xml::decode::try_data(&mut child).map_err(|e| SerdeError::InvalidInput {
                message: e.to_string(),
            })?;
        inner.push_str(&format!("<{}>{}</{}>", name, text, name));
    }
    Ok(format!("<R>{}</R>", inner))
}

struct OwnedXmlDeserializer {
    data: String,
}

impl ShapeDeserializer for OwnedXmlDeserializer {
    fn read_struct(
        &mut self,
        schema: &Schema,
        consumer: &mut dyn FnMut(&Schema, &mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let mut deser = aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes());
        deser.read_struct(schema, consumer)
    }

    fn read_list(
        &mut self,
        schema: &Schema,
        consumer: &mut dyn FnMut(&mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let mut deser = aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes());
        deser.read_list(schema, consumer)
    }

    fn read_map(
        &mut self,
        schema: &Schema,
        consumer: &mut dyn FnMut(String, &mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let mut deser = aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes());
        deser.read_map(schema, consumer)
    }

    fn read_boolean(&mut self, schema: &Schema) -> Result<bool, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_boolean(schema)
    }

    fn read_byte(&mut self, schema: &Schema) -> Result<i8, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_byte(schema)
    }

    fn read_short(&mut self, schema: &Schema) -> Result<i16, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_short(schema)
    }

    fn read_integer(&mut self, schema: &Schema) -> Result<i32, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_integer(schema)
    }

    fn read_long(&mut self, schema: &Schema) -> Result<i64, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_long(schema)
    }

    fn read_float(&mut self, schema: &Schema) -> Result<f32, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_float(schema)
    }

    fn read_double(&mut self, schema: &Schema) -> Result<f64, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_double(schema)
    }

    fn read_big_integer(&mut self, schema: &Schema) -> Result<BigInteger, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes())
            .read_big_integer(schema)
    }

    fn read_big_decimal(&mut self, schema: &Schema) -> Result<BigDecimal, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes())
            .read_big_decimal(schema)
    }

    fn read_string(&mut self, schema: &Schema) -> Result<String, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_string(schema)
    }

    fn read_blob(&mut self, schema: &Schema) -> Result<Blob, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_blob(schema)
    }

    fn read_timestamp(&mut self, schema: &Schema) -> Result<DateTime, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes())
            .read_timestamp(schema)
    }

    fn read_document(&mut self, schema: &Schema) -> Result<Document, SerdeError> {
        aws_smithy_xml::codec::XmlShapeDeserializer::new(self.data.as_bytes()).read_document(schema)
    }

    fn is_null(&self) -> bool {
        self.data.is_empty()
    }
    fn read_null(&mut self) -> Result<(), SerdeError> {
        Ok(())
    }
    fn container_size(&self) -> Option<usize> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aws_smithy_schema::protocol::ClientProtocolInner;
    use aws_smithy_schema::serde::ShapeSerializer;
    use aws_smithy_schema::ShapeType;
    use aws_smithy_types::config_bag::Layer;

    struct EmptyInput;
    impl SerializableStruct for EmptyInput {
        fn serialize_members(&self, _: &mut dyn ShapeSerializer) -> Result<(), SerdeError> {
            Ok(())
        }
    }

    static SCHEMA: Schema = Schema::new(shape_id!("test", "Input"), ShapeType::Structure);

    fn cfg_with_metadata() -> ConfigBag {
        let mut layer = Layer::new("test");
        layer.store_put(Metadata::new("GetUser", "MyService"));
        ConfigBag::of_layers(vec![layer])
    }

    #[test]
    fn request_has_correct_content_type() {
        let cfg = cfg_with_metadata();
        let request = AwsQueryProtocol::new("2012-11-05")
            .serialize_request(&EmptyInput, &SCHEMA, "https://example.com", &cfg)
            .unwrap();
        assert_eq!(
            request.headers().get("Content-Type").unwrap(),
            "application/x-www-form-urlencoded"
        );
    }

    #[test]
    fn request_has_action_and_version() {
        let cfg = cfg_with_metadata();
        let request = AwsQueryProtocol::new("2012-11-05")
            .serialize_request(&EmptyInput, &SCHEMA, "https://example.com", &cfg)
            .unwrap();
        let body = std::str::from_utf8(request.body().bytes().unwrap()).unwrap();
        assert!(body.contains("Action=GetUser"));
        assert!(body.contains("Version=2012-11-05"));
    }

    #[test]
    fn request_posts_to_endpoint() {
        let cfg = cfg_with_metadata();
        let request = AwsQueryProtocol::new("1.0")
            .serialize_request(
                &EmptyInput,
                &SCHEMA,
                "https://sqs.us-east-1.amazonaws.com",
                &cfg,
            )
            .unwrap();
        assert_eq!(request.uri(), "https://sqs.us-east-1.amazonaws.com");
    }

    #[test]
    fn request_defaults_to_slash() {
        let cfg = cfg_with_metadata();
        let request = AwsQueryProtocol::new("1.0")
            .serialize_request(&EmptyInput, &SCHEMA, "", &cfg)
            .unwrap();
        assert_eq!(request.uri(), "/");
    }

    #[test]
    fn deserialize_response_strips_envelope() {
        let xml = "<GetUserResponse><GetUserResult><Name>Alice</Name><Age>30</Age></GetUserResult></GetUserResponse>";
        let response = Response::new(200u16.try_into().unwrap(), SdkBody::from(xml));

        static NAME: Schema = Schema::new_member(shape_id!("t", "S"), ShapeType::String, "Name", 0);
        static AGE: Schema = Schema::new_member(shape_id!("t", "S"), ShapeType::Integer, "Age", 1);
        static OUT_SCHEMA: Schema =
            Schema::new_struct(shape_id!("t", "S"), ShapeType::Structure, &[&NAME, &AGE]);

        let mut deser = AwsQueryProtocol::new("1.0")
            .deserialize_response(&response, &OUT_SCHEMA, &ConfigBag::base())
            .unwrap();
        let mut name = String::new();
        let mut age = 0i32;
        deser
            .read_struct(&OUT_SCHEMA, &mut |member, d| {
                match member.member_name() {
                    Some("Name") => name = d.read_string(member)?,
                    Some("Age") => age = d.read_integer(member)?,
                    _ => {}
                }
                Ok(())
            })
            .unwrap();
        assert_eq!(name, "Alice");
        assert_eq!(age, 30);
    }

    #[test]
    fn protocol_id() {
        assert_eq!(
            AwsQueryProtocol::new("1.0").protocol_id().as_str(),
            "aws.protocols#awsQuery"
        );
    }
}
