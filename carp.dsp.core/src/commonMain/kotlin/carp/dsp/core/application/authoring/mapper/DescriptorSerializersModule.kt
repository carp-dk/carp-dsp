package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.ArgTokenDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * [SerializersModule] required to serialize [carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor] to and from JSON/YAML.
 *
 * Register this module with your [kotlinx.serialization.json.Json] instance whenever
 * you need to encode or decode a [carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor] that contains sealed subtypes:
 *
 * ```kotlin
 * val json = Json { serializersModule = descriptorSerializersModule }
 * val encoded = json.encodeToString(WorkflowDescriptor.serializer(), descriptor)
 * val decoded = json.decodeFromString(WorkflowDescriptor.serializer(), encoded)
 * ```
 *
 * Registered hierarchies:
 * - [TaskDescriptor] → [CommandTaskDescriptor], [PythonTaskDescriptor], [InProcessTaskDescriptor]
 * - [PythonEntryPointDescriptor] → [ScriptEntryPointDescriptor], [ModuleEntryPointDescriptor]
 * - [ArgTokenDescriptor] → [LiteralArgDescriptor], [InputRefArgDescriptor],
 *   [OutputRefArgDescriptor], [ParamRefArgDescriptor]
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
}

