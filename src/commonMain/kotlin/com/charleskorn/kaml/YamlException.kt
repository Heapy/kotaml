/*

   Copyright 2018-2023 Charles Korn.
   Copyright 2026 Ruslan Ibrahimau.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.charleskorn.kaml

import kotlinx.serialization.SerializationException

/**
 * A YAML failure with a [path] identifying the value and [line]/[column] locating it in the source.
 * [toString] includes the position and error message.
 */
public open class YamlException(
    override val message: String,
    public val path: YamlPath,
    override val cause: Throwable? = null,
) : SerializationException(message, cause) {
    public val location: Location = path.endLocation
    public val line: Int = location.line
    public val column: Int = location.column

    override fun toString(): String = "${this::class.simpleName} at ${path.toHumanReadableString()} on line $line, column $column: $message"
}

/**
 * A mapping contains more than one scalar key with the same text.
 *
 * The inherited [path] points to [duplicatePath]; [originalPath] identifies the earlier occurrence.
 * [key] is the key's diagnostic representation, which may include quotes.
 */
public class DuplicateKeyException(
    public val originalPath: YamlPath,
    public val duplicatePath: YamlPath,
    public val key: String,
) : YamlException(
        "Duplicate key $key. It was previously given at line ${originalPath.endLocation.line}, column ${originalPath.endLocation.column}.",
        duplicatePath,
    ) {
    public val originalLocation: Location = originalPath.endLocation
    public val duplicateLocation: Location = duplicatePath.endLocation
}

/** Parsing found no YAML document in the input stream, for example in empty or comment-only input. */
public class EmptyYamlDocumentException(
    message: String,
    path: YamlPath,
) : YamlException(message, path)

/**
 * A mapping value could not be decoded for [propertyName].
 * [path] locates the offending value; [cause] preserves the underlying decoding failure when available.
 */
public class InvalidPropertyValueException(
    public val propertyName: String,
    public val reason: String,
    path: YamlPath,
    cause: Throwable? = null,
) : YamlException("Value for '$propertyName' is invalid: $reason", path, cause)

/** YAML syntax or document structure cannot be read, including constructs unsupported by the node reader. */
public class MalformedYamlException(
    message: String,
    path: YamlPath,
) : YamlException(message, path)

/** A YAML null was encountered where decoding required a non-null value. */
public class UnexpectedNullValueException(
    path: YamlPath,
) : YamlException("Unexpected null or empty value for non-null field.", path)

/** A required [propertyName] is absent; [path] identifies the containing value rather than a missing token. */
public class MissingRequiredPropertyException(
    public val propertyName: String,
    path: YamlPath,
    cause: Throwable? = null,
) : YamlException("Property '$propertyName' is required but it is missing.", path, cause)

/**
 * An unknown object property rejected in strict mode.
 * [validPropertyNames] reflects the configured [YamlNamingStrategy]; [path] locates the key.
 */
public class UnknownPropertyException(
    public val propertyName: String,
    public val validPropertyNames: Set<String>,
    path: YamlPath,
) : YamlException("Unknown property '$propertyName'. Known properties are: ${validPropertyNames.sorted().joinToString(", ")}", path)

/**
 * No subtype serializer was found for discriminator [typeName].
 * [path] locates the discriminator; [validTypeNames] lists names known to the descriptor or serializers module.
 */
public class UnknownPolymorphicTypeException(
    public val typeName: String,
    public val validTypeNames: Set<String>,
    path: YamlPath,
    cause: Throwable? = null,
) : YamlException("Unknown type '$typeName'. Known types are: ${validTypeNames.sorted().joinToString(", ")}", path, cause)

/**
 * Scalar text cannot represent the requested primitive or enum value, including an out-of-range integer.
 * [originalValue] retains the unconverted text for diagnostics.
 */
public class YamlScalarFormatException(
    message: String,
    path: YamlPath,
    public val originalValue: String,
) : YamlException(message, path)

/** A YAML node's shape or tag does not match the type required by a decoder or node accessor. */
public open class IncorrectTypeException(
    message: String,
    path: YamlPath,
) : YamlException(message, path)

/** Polymorphic decoding requires a type tag, but the supplied YAML value has none. */
public class MissingTypeTagException(
    path: YamlPath,
) : IncorrectTypeException("Value is missing a type tag (eg. !<type>)", path)

/** An alias refers to [anchorName] before a corresponding anchored value is available to the reader. */
public class UnknownAnchorException(
    public val anchorName: String,
    path: YamlPath,
) : YamlException("Unknown anchor '$anchorName'.", path)

/** A root mapping [key] matches [extensionDefinitionPrefix] but its value does not define an anchor. */
public class NoAnchorForExtensionException(
    public val key: String,
    public val extensionDefinitionPrefix: String,
    path: YamlPath,
) : YamlException(
        "The key '$key' starts with the extension definition prefix '$extensionDefinitionPrefix' but does not define an anchor.",
        path,
    )

/** An anchor or alias violates the configured policy, either by being disabled or exceeding the alias limit. */
public class ForbiddenAnchorOrAliasException(
    message: String,
    path: YamlPath,
) : YamlException(message, path)
