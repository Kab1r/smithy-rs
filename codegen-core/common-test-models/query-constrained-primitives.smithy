$version: "1.0"

namespace aws.protocoltests.query.constrained

use aws.protocols#awsQuery
use aws.protocols#ec2Query
use smithy.api#xmlNamespace
use smithy.framework#ValidationException
use smithy.test#httpMalformedRequestTests

/// AWS Query protocol tests for constrained primitive members.
@awsQuery
@xmlNamespace(uri: "https://example.com/")
service AwsQueryConstrained {
    version: "2020-01-08",
    operations: [
        ConstrainedPrimitiveInput,
        ConstrainedPrimitiveOutput,
    ],
}

/// EC2 Query protocol tests for constrained primitive members.
@ec2Query
@xmlNamespace(uri: "https://example.com/")
service Ec2QueryConstrained {
    version: "2020-01-08",
    operations: [
        ConstrainedPrimitiveInput,
        ConstrainedPrimitiveOutput,
    ],
}

@http(uri: "/", method: "POST")
@httpMalformedRequestTests([
    {
        id: "AwsQueryConstrainedPrimitiveInputInvalidRange",
        documentation: "Constraint violations are rejected after query parsing.",
        protocol: awsQuery,
        request: {
            method: "POST",
            uri: "/",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
            },
            body: "Action=ConstrainedPrimitiveInput&Version=2020-01-08&durationSeconds=0&name=example",
        },
        response: {
            code: 400,
        },
    },
    {
        id: "Ec2QueryConstrainedPrimitiveInputInvalidRange",
        documentation: "EC2 constraint violations are rejected after query parsing.",
        protocol: ec2Query,
        request: {
            method: "POST",
            uri: "/",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
            },
            body: "Action=ConstrainedPrimitiveInput&Version=2020-01-08&DurationSeconds=0&Name=example",
        },
        response: {
            code: 400,
        },
    },
])
operation ConstrainedPrimitiveInput {
    input: ConstrainedPrimitiveInputInput,
    output: ConstrainedPrimitiveInputOutput,
    errors: [ValidationException],
}

structure ConstrainedPrimitiveInputInput {
    durationSeconds: DurationSecondsType,
    name: NameType,
}

structure ConstrainedPrimitiveInputOutput {}

@http(uri: "/output", method: "POST")
operation ConstrainedPrimitiveOutput {
    input: ConstrainedPrimitiveOutputInput,
    output: ConstrainedPrimitiveOutputOutput,
    errors: [ValidationException],
}

structure ConstrainedPrimitiveOutputInput {}

structure ConstrainedPrimitiveOutputOutput {
    @required
    count: NonNegativeIntegerType,
}

@range(min: 1, max: 43200)
integer DurationSecondsType

@range(min: 0)
integer NonNegativeIntegerType

@pattern("^[A-Za-z0-9]+$")
string NameType
