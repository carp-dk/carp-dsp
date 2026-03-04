package carp.dsp.core.application.authoring.serialization

import carp.dsp.core.application.authoring.descriptor.ArgTokenDescriptor
import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableOutputDestination
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.InputSource
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputDestination
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * [SerializersModule] for the full [WorkflowDescriptor] sealed-type hierarchy.
 *
 * This is a **serialization concern** — it lives here rather than in the mapper package
 * so that callers (YAML I/O, tests, future importers) can import it independently of the
 * exporter implementation.
 *
 * Each sealed interface emits a stable discriminator field `"type"` — configured via
 * `polymorphismStyle = PolymorphismStyle.Property` and `polymorphismPropertyName = "type"`
 * in [descriptorYaml] — so the emitted document always reads `type: command`, `type: python`,
 * etc., predictably across refactors and library upgrades.
 *
 * Registered hierarchies:
 * - [TaskDescriptor]            → [CommandTaskDescriptor], [PythonTaskDescriptor], [InProcessTaskDescriptor]
 * - [PythonEntryPointDescriptor]→ [ScriptEntryPointDescriptor], [ModuleEntryPointDescriptor]
 * - [ArgTokenDescriptor]        → [LiteralArgDescriptor], [InputRefArgDescriptor],
 *                                 [OutputRefArgDescriptor], [ParamRefArgDescriptor]
 */
val descriptorSerializersModule: SerializersModule = SerializersModule {
    polymorphic(TaskDescriptor::class) {
        subclass(CommandTaskDescriptor::class)
        subclass(PythonTaskDescriptor::class)
        subclass(InProcessTaskDescriptor::class)
    }
    polymorphic(PythonEntryPointDescriptor::class) {
        subclass(ScriptEntryPointDescriptor::class)
        subclass(ModuleEntryPointDescriptor::class)
    }
    polymorphic(ArgTokenDescriptor::class) {
        subclass(LiteralArgDescriptor::class)
        subclass(InputRefArgDescriptor::class)
        subclass(OutputRefArgDescriptor::class)
        subclass(ParamRefArgDescriptor::class)
    }
    polymorphic(InputSource::class) {
        subclass(FileInputSource::class)
        subclass(StepOutputInputSource::class)
        subclass(EnvironmentVariableInputSource::class)
    }
    polymorphic(OutputDestination::class) {
        subclass(FileOutputDestination::class)
        subclass(EnvironmentVariableOutputDestination::class)
    }
}

/**
 * Pre-configured [Yaml] instance for encoding and decoding [WorkflowDescriptor] documents.
 *
 * The polymorphic discriminator field is explicitly pinned to `"type"` via two
 * [YamlConfiguration] parameters:
 * - `polymorphismStyle = PolymorphismStyle.Property` — use an inline property key rather
 *   than a YAML tag.
 * - `polymorphismPropertyName = "type"` — name the property `"type"` explicitly, rather
 *   than relying on the library default. This makes the YAML contract independent of any
 *   future change to that default.
 *
 * `@JsonClassDiscriminator` is a JSON-only annotation and cannot be used with kaml.
 *
 * [YamlConfiguration.encodeDefaults] is `false` so optional fields at their default
 * values are omitted from authored documents (keeps YAML minimal).
 *
 * Usage:
 * ```kotlin
 * val encoded = descriptorYaml.encodeToString(WorkflowDescriptor.serializer(), descriptor)
 * val decoded = descriptorYaml.decodeFromString(WorkflowDescriptor.serializer(), encoded)
 * ```
 */
val descriptorYaml: Yaml = Yaml(
    serializersModule = descriptorSerializersModule,
    configuration = YamlConfiguration(
        polymorphismStyle = PolymorphismStyle.Property,
        polymorphismPropertyName = "type",
        encodeDefaults = false,
        strictMode = false,
    )
)
