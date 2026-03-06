package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable


/**
 * Lightweight descriptor for the type and format of data flowing through a port.
 *
 * @property type MIME type or domain type string (e.g. `"text/csv"`).
 * @property format Optional sub-format or dialect identifier.
 * @property schemaRef URI or short-name pointing to an external schema definition.
 * @property ontologyRef URI or CURIe pointing to an ontology concept.
 * @property notes Free-text documentation for this data port.
 */
@Serializable
data class DataDescriptor(
    val type: String? = null,
    val format: String? = null,
    val schemaRef: String? = null,
    val ontologyRef: String? = null,
    val notes: String? = null,
)
