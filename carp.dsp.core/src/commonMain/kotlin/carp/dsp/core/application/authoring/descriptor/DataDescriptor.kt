package carp.dsp.core.application.authoring.descriptor

import dk.cachet.carp.common.application.data.DataType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


/**
 * Reference to an ontology concept, by resolvable IRI.
 *
 * @property iri Resolvable term IRI or CURIe.
 */
@Serializable( with = OntologyRef.Serializer::class )
data class OntologyRef( val iri: String )
{
    init { require( iri.isNotBlank() ) { "OntologyRef iri must not be blank" } }

    override fun toString(): String = iri

    internal object Serializer : KSerializer<OntologyRef>
    {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor( "OntologyRef", PrimitiveKind.STRING )

        override fun serialize( encoder: Encoder, value: OntologyRef ) =
            encoder.encodeString( value.iri )

        override fun deserialize( decoder: Decoder ): OntologyRef =
            OntologyRef( decoder.decodeString() )
    }
}


/**
 * One typed field inside a data artefact: a column of a table, a key of a JSON
 * record, or a named stream.
 *
 * @property name Column name, JSON key, or stream id within the artifact.
 * @property dataType CARP domain data type this field carries (
 *   [dk.cachet.carp.common.application.data.DataType]). Null when the platform
 *   does not model the field's type - a generic column, or open/external data
 *   identified only by [ontologyRef].
 * @property ontologyRef Ontology term for what this field means. Vocabulary-agnostic,
 * so it applies to CARP and non-CARP data alike.
 */
@Serializable
data class DataField(
    val name: String,
    val dataType: DataType? = null,
    val ontologyRef: OntologyRef? = null,
)


/**
 * Descriptor for the data flowing through a port.
 *
 * Three layers, kept distinct:
 * - serialization ([fileFormat], [encoding]) - how the bytes are laid out;
 * - the format's identity ([formatRef]) - an ontology term for the container;
 * - content ([fields]) - the typed fields inside, each with its own meaning.
 *
 * @property fileFormat Stored format, e.g. `"csv"`, `"json"`, `"text/csv"`. Maps
 *   to the `FileFormat` enum at import time.
 * @property encoding Character encoding, e.g. `"utf-8"`. Defaults to UTF-8 at
 *   import time when absent.
 * @property formatRef Ontology term for the file format itself (e.g. the EDAM
 *   term for CSV).
 * @property fields The typed fields the artefact carries. Empty for an
 *   open/generic table.
 * @property notes Free-text for documentation.
 */
@Serializable
data class DataDescriptor(
    val fileFormat: String? = null,
    val encoding: String? = null,
    val formatRef: OntologyRef? = null,
    val fields: List<DataField> = emptyList(),
    val notes: String? = null,
)
