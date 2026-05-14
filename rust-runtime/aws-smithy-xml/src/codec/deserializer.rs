/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::decode::{try_data, Document as XmlDocument, ScopedDecoder};
use aws_smithy_schema::serde::{SerdeError, ShapeDeserializer};
use aws_smithy_schema::Schema;
use aws_smithy_types::{BigDecimal, BigInteger, Blob, DateTime, Document};

pub struct XmlShapeDeserializer<'a> {
    input: &'a [u8],
}

impl<'a> XmlShapeDeserializer<'a> {
    pub fn new(input: &'a [u8]) -> Self {
        Self { input }
    }

    fn as_str(&self) -> Result<&'a str, SerdeError> {
        std::str::from_utf8(self.input).map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })
    }
}

fn resolve_xml_member<'a>(schema: &'a Schema, element_name: &str) -> Option<&'a Schema> {
    schema
        .members()
        .iter()
        .find(|m| {
            let wire_name = m
                .xml_name()
                .map(|t| t.value())
                .or(m.member_name())
                .unwrap_or("");
            wire_name == element_name
        })
        .copied()
}

fn read_text(scope: &mut ScopedDecoder<'_, '_>) -> Result<String, SerdeError> {
    try_data(scope)
        .map(|cow| cow.into_owned())
        .map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })
}

impl<'a> ShapeDeserializer for XmlShapeDeserializer<'a> {
    fn read_struct(
        &mut self,
        schema: &Schema,
        consumer: &mut dyn FnMut(&Schema, &mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let xml_str = self.as_str()?;
        if xml_str.is_empty() {
            return Ok(());
        }
        let mut doc = XmlDocument::new(xml_str);
        let mut root = doc.root_element().map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })?;
        while let Some(mut tag) = root.next_tag() {
            let local_name = tag.start_el().local().to_string();
            if let Some(member_schema) = resolve_xml_member(schema, &local_name) {
                if member_schema.shape_type() == aws_smithy_schema::ShapeType::Structure {
                    // For nested structs, we need to reconstruct the XML subtree.
                    // Collect child elements as XML string.
                    let mut inner_xml = String::new();
                    while let Some(mut child) = tag.next_tag() {
                        let child_name = child.start_el().local().to_string();
                        let text = read_text(&mut child)?;
                        inner_xml.push_str(&format!("<{}>{}</{}>", child_name, text, child_name));
                    }
                    let wrapped = format!("<S>{}</S>", inner_xml);
                    let mut sub = XmlShapeDeserializer::new(wrapped.as_bytes());
                    consumer(member_schema, &mut sub)?;
                } else {
                    let text = read_text(&mut tag)?;
                    let mut sub = XmlShapeDeserializer::new(text.as_bytes());
                    consumer(member_schema, &mut sub)?;
                }
            }
        }
        Ok(())
    }

    fn read_list(
        &mut self,
        _schema: &Schema,
        consumer: &mut dyn FnMut(&mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let xml_str = self.as_str()?;
        if xml_str.is_empty() {
            return Ok(());
        }
        let mut doc = XmlDocument::new(xml_str);
        let mut root = doc.root_element().map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })?;
        while let Some(mut tag) = root.next_tag() {
            let text = read_text(&mut tag)?;
            if text.is_empty() {
                let mut sub = XmlShapeDeserializer::new(b"");
                consumer(&mut sub)?;
            } else {
                let mut sub = XmlShapeDeserializer::new(text.as_bytes());
                consumer(&mut sub)?;
            }
        }
        Ok(())
    }

    fn read_map(
        &mut self,
        _schema: &Schema,
        consumer: &mut dyn FnMut(String, &mut dyn ShapeDeserializer) -> Result<(), SerdeError>,
    ) -> Result<(), SerdeError> {
        let xml_str = self.as_str()?;
        if xml_str.is_empty() {
            return Ok(());
        }
        let mut doc = XmlDocument::new(xml_str);
        let mut root = doc.root_element().map_err(|e| SerdeError::InvalidInput {
            message: e.to_string(),
        })?;
        while let Some(mut entry_tag) = root.next_tag() {
            let mut key = String::new();
            let mut value_text = String::new();
            while let Some(mut inner) = entry_tag.next_tag() {
                let local = inner.start_el().local().to_string();
                let text = read_text(&mut inner)?;
                match local.as_str() {
                    "key" => key = text,
                    "value" => value_text = text,
                    _ => {}
                }
            }
            if !key.is_empty() {
                let mut sub = XmlShapeDeserializer::new(value_text.as_bytes());
                consumer(key, &mut sub)?;
            }
        }
        Ok(())
    }

    fn read_boolean(&mut self, _schema: &Schema) -> Result<bool, SerdeError> {
        match self.as_str()?.trim() {
            "true" => Ok(true),
            "false" => Ok(false),
            other => Err(SerdeError::TypeMismatch {
                message: format!("expected boolean, got: {other}"),
            }),
        }
    }

    fn read_byte(&mut self, _schema: &Schema) -> Result<i8, SerdeError> {
        self.as_str()?
            .trim()
            .parse()
            .map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            })
    }

    fn read_short(&mut self, _schema: &Schema) -> Result<i16, SerdeError> {
        self.as_str()?
            .trim()
            .parse()
            .map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            })
    }

    fn read_integer(&mut self, _schema: &Schema) -> Result<i32, SerdeError> {
        self.as_str()?
            .trim()
            .parse()
            .map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            })
    }

    fn read_long(&mut self, _schema: &Schema) -> Result<i64, SerdeError> {
        self.as_str()?
            .trim()
            .parse()
            .map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            })
    }

    fn read_float(&mut self, _schema: &Schema) -> Result<f32, SerdeError> {
        let s = self.as_str()?.trim();
        match s {
            "NaN" => Ok(f32::NAN),
            "Infinity" => Ok(f32::INFINITY),
            "-Infinity" => Ok(f32::NEG_INFINITY),
            _ => s.parse().map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            }),
        }
    }

    fn read_double(&mut self, _schema: &Schema) -> Result<f64, SerdeError> {
        let s = self.as_str()?.trim();
        match s {
            "NaN" => Ok(f64::NAN),
            "Infinity" => Ok(f64::INFINITY),
            "-Infinity" => Ok(f64::NEG_INFINITY),
            _ => s.parse().map_err(|e| SerdeError::InvalidInput {
                message: format!("{e}"),
            }),
        }
    }

    fn read_big_integer(&mut self, _schema: &Schema) -> Result<BigInteger, SerdeError> {
        use std::str::FromStr;
        BigInteger::from_str(self.as_str()?.trim()).map_err(|e| SerdeError::InvalidInput {
            message: format!("{e}"),
        })
    }

    fn read_big_decimal(&mut self, _schema: &Schema) -> Result<BigDecimal, SerdeError> {
        use std::str::FromStr;
        BigDecimal::from_str(self.as_str()?.trim()).map_err(|e| SerdeError::InvalidInput {
            message: format!("{e}"),
        })
    }

    fn read_string(&mut self, _schema: &Schema) -> Result<String, SerdeError> {
        Ok(self.as_str()?.to_string())
    }

    fn read_blob(&mut self, _schema: &Schema) -> Result<Blob, SerdeError> {
        let decoded = aws_smithy_types::base64::decode(self.as_str()?.trim()).map_err(|e| {
            SerdeError::InvalidInput {
                message: format!("invalid base64: {e}"),
            }
        })?;
        Ok(Blob::new(decoded))
    }

    fn read_timestamp(&mut self, schema: &Schema) -> Result<DateTime, SerdeError> {
        let s = self.as_str()?.trim();
        let format = if let Some(ts_trait) = schema.timestamp_format() {
            match ts_trait.format() {
                aws_smithy_schema::traits::TimestampFormat::EpochSeconds => {
                    aws_smithy_types::date_time::Format::EpochSeconds
                }
                aws_smithy_schema::traits::TimestampFormat::HttpDate => {
                    aws_smithy_types::date_time::Format::HttpDate
                }
                aws_smithy_schema::traits::TimestampFormat::DateTime => {
                    aws_smithy_types::date_time::Format::DateTime
                }
            }
        } else {
            aws_smithy_types::date_time::Format::DateTime
        };
        DateTime::from_str(s, format)
            .map_err(|e| SerdeError::custom(format!("invalid timestamp: {e}")))
    }

    fn read_document(&mut self, _schema: &Schema) -> Result<Document, SerdeError> {
        Err(SerdeError::UnsupportedOperation {
            message: "documents not supported in XML".into(),
        })
    }

    fn is_null(&self) -> bool {
        self.input.is_empty()
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
    use aws_smithy_schema::{shape_id, ShapeType};

    static NAME_MEMBER: Schema =
        Schema::new_member(shape_id!("test", "S"), ShapeType::String, "Name", 0);
    static AGE_MEMBER: Schema =
        Schema::new_member(shape_id!("test", "S"), ShapeType::Integer, "Age", 1);
    static SCHEMA: Schema = Schema::new_struct(
        shape_id!("test", "S"),
        ShapeType::Structure,
        &[&NAME_MEMBER, &AGE_MEMBER],
    );

    #[test]
    fn read_simple_struct() {
        let xml = b"<Result><Name>Alice</Name><Age>30</Age></Result>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut name = String::new();
        let mut age = 0i32;
        deser
            .read_struct(&SCHEMA, &mut |member, d| {
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
    fn read_nested_struct() {
        static STREET: Schema =
            Schema::new_member(shape_id!("t", "Addr"), ShapeType::String, "Street", 0);
        static CITY: Schema =
            Schema::new_member(shape_id!("t", "Addr"), ShapeType::String, "City", 1);
        static ADDR_SCHEMA: Schema = Schema::new_struct(
            shape_id!("t", "Addr"),
            ShapeType::Structure,
            &[&STREET, &CITY],
        );
        static ADDR_MEMBER: Schema =
            Schema::new_member(shape_id!("t", "S"), ShapeType::Structure, "Address", 0);
        static OUTER: Schema =
            Schema::new_struct(shape_id!("t", "S"), ShapeType::Structure, &[&ADDR_MEMBER]);

        let xml =
            b"<Result><Address><Street>123 Main</Street><City>Seattle</City></Address></Result>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut street = String::new();
        let mut city = String::new();
        deser
            .read_struct(&OUTER, &mut |member, d| {
                if member.member_name() == Some("Address") {
                    d.read_struct(&ADDR_SCHEMA, &mut |inner_member, d2| {
                        match inner_member.member_name() {
                            Some("Street") => street = d2.read_string(inner_member)?,
                            Some("City") => city = d2.read_string(inner_member)?,
                            _ => {}
                        }
                        Ok(())
                    })?;
                }
                Ok(())
            })
            .unwrap();
        assert_eq!(street, "123 Main");
        assert_eq!(city, "Seattle");
    }

    #[test]
    fn read_list() {
        let xml = b"<Items><member>foo</member><member>bar</member></Items>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut items = Vec::new();
        deser
            .read_list(&aws_smithy_schema::prelude::STRING, &mut |d| {
                items.push(d.read_string(&aws_smithy_schema::prelude::STRING)?);
                Ok(())
            })
            .unwrap();
        assert_eq!(items, vec!["foo", "bar"]);
    }

    #[test]
    fn read_map() {
        let xml = b"<Tags><entry><key>color</key><value>red</value></entry><entry><key>size</key><value>large</value></entry></Tags>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut map = std::collections::HashMap::new();
        deser
            .read_map(&aws_smithy_schema::prelude::STRING, &mut |key, d| {
                map.insert(key, d.read_string(&aws_smithy_schema::prelude::STRING)?);
                Ok(())
            })
            .unwrap();
        assert_eq!(map.get("color").unwrap(), "red");
        assert_eq!(map.get("size").unwrap(), "large");
    }

    #[test]
    fn read_boolean() {
        let mut deser = XmlShapeDeserializer::new(b"true");
        assert!(deser
            .read_boolean(&aws_smithy_schema::prelude::BOOLEAN)
            .unwrap());
    }

    #[test]
    fn read_float_special_values() {
        let mut deser = XmlShapeDeserializer::new(b"NaN");
        assert!(deser
            .read_float(&aws_smithy_schema::prelude::FLOAT)
            .unwrap()
            .is_nan());

        let mut deser = XmlShapeDeserializer::new(b"Infinity");
        assert_eq!(
            deser
                .read_float(&aws_smithy_schema::prelude::FLOAT)
                .unwrap(),
            f32::INFINITY
        );
    }

    #[test]
    fn empty_input_as_empty_struct() {
        let mut deser = XmlShapeDeserializer::new(b"");
        deser.read_struct(&SCHEMA, &mut |_, _| Ok(())).unwrap();
    }

    #[test]
    fn unknown_elements_skipped() {
        let xml = b"<Result><Unknown>skip</Unknown><Name>Bob</Name></Result>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut name = String::new();
        deser
            .read_struct(&SCHEMA, &mut |member, d| {
                if member.member_name() == Some("Name") {
                    name = d.read_string(member)?;
                }
                Ok(())
            })
            .unwrap();
        assert_eq!(name, "Bob");
    }

    #[test]
    fn xml_name_trait() {
        static MEMBER: Schema =
            Schema::new_member(shape_id!("t", "S"), ShapeType::String, "myField", 0)
                .with_xml_name("CustomName");
        static S: Schema =
            Schema::new_struct(shape_id!("t", "S"), ShapeType::Structure, &[&MEMBER]);

        let xml = b"<Result><CustomName>hello</CustomName></Result>";
        let mut deser = XmlShapeDeserializer::new(xml);
        let mut val = String::new();
        deser
            .read_struct(&S, &mut |member, d| {
                val = d.read_string(member)?;
                Ok(())
            })
            .unwrap();
        assert_eq!(val, "hello");
    }
}
