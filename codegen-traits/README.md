# codegen-traits

This module defines [Smithy traits] that are evaluated by the smithy-rs code generators
without depending on either `codegen-client` or `codegen-server`. The current consumers
are server-side only:

- `@validationException`, `@validationMessage`, `@validationFieldList`,
  `@validationFieldName`, `@validationFieldMessage` — describe how a user-modeled
  validation exception maps onto the server framework's machinery.
- `@validationExceptionMemberDefault` — provides default values for additional members on
  user-modeled validation exceptions.

The module exists as a standalone Gradle subproject so that future client- or
Python-side consumers can depend on the trait definitions without pulling in the
server codegen surface. If no such consumer emerges within a reasonable horizon, the
module should be folded into `codegen-server`.

[Smithy traits]: https://smithy.io/2.0/spec/model.html#traits
