/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.format

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.FormatStreamModelToStringFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable
import app.morphe.util.findMethodFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/VideoFormat;"
private const val EXTENSION_FORMAT_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoFormat$FormatInterface;"

private lateinit var adaptiveFormatMethod : WeakReference<MutableMethod>

val videoFormatPatch = bytecodePatch(
    description = "Hooks YouTube to get format about the current playing video.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val formatStreamModelFormatField = FormatStreamModelToStringFingerprint
            .instructionMatches[5]
            .instruction
            .getReference<FieldReference>()!!

        getFormatFingerprint(formatStreamModelFormatField.type).classDef.apply {
            interfaces.add(EXTENSION_FORMAT_INTERFACE)

            mapOf(
                "width" to "patch_getWidth",
                "height" to "patch_getHeight",
                "mimeType" to "patch_getMimeType",
                "itag" to "patch_getItag"
            ).forEach { (protoFieldName, patchInterfaceMethodName) ->
                val targetMethod =
                    FormatStreamModelToStringFingerprint.originalMethod.findMethodFromToString(protoFieldName)

                val instructionMatches = getFormatStreamModelMethodFingerprint(
                    formatStreamModelFormatField, targetMethod
                ).instructionMatches

                val fieldInstruction = instructionMatches[1].instruction as Instruction22c
                val returnOpcode = instructionMatches[2].instruction.opcode.name

                methods.add(
                    ImmutableMethod(
                        type,
                        patchInterfaceMethodName,
                        listOf(),
                        targetMethod.returnType,
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                ${fieldInstruction.opcode.name} v0, p0, ${fieldInstruction.reference}
                                $returnOpcode v0
                            """
                        )
                    }
                )
            }
        }

        VideoStreamingDataConstructorFingerprint.let {
            val originalMethod = it.method
            val originalRegisterCount = originalMethod.implementation!!.registerCount
            // Keep the matched videoId field reference before cloning changes instruction positions.
            val videoIdField = it.instructionMatches[1].instruction.getReference<FieldReference>()!!

            // Keep the original register layout and add four contiguous scratch registers.
            // cloneMutable also preserves the original parameter values after register growth.
            val clonedMethod = originalMethod.cloneMutable(
                additionalRegisters = originalMethod.numberOfParameterRegisters + 4
            )
            mutableClassDefBy(originalMethod.definingClass).methods.apply {
                remove(originalMethod)
                add(clonedMethod)
            }

            clonedMethod.apply {
                // cloneMutable inserts one move per logical parameter at the method start.
                val matchIndexOffset = numberOfParameterRegistersLogical
                val firstStreamingDataIndex = it.instructionMatches.first().index + matchIndexOffset
                val adaptiveFormatsIndex = it.instructionMatches.last().index + matchIndexOffset

                check(firstStreamingDataIndex < adaptiveFormatsIndex) {
                    "StreamingData field reads are not in the expected order"
                }

                // For iget-object, registerA is the field value; registerB is the receiver.
                val firstStreamingDataRegister =
                    getInstruction<TwoRegisterInstruction>(firstStreamingDataIndex).registerA
                val lastStreamingDataInstruction =
                    getInstruction<TwoRegisterInstruction>(adaptiveFormatsIndex)
                val lastStreamingDataRegister = lastStreamingDataInstruction.registerB
                val adaptiveFormatsRegister = lastStreamingDataInstruction.registerA
                // cloneMutable copies parameters back into their original register slots.
                // The newly allocated free registers start at the original register count.
                val scratchStart = originalRegisterCount
                val insertIndex = adaptiveFormatsIndex + 1

                // Capture the actual field values into a contiguous range.
                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        move-object/16 v${scratchStart + 1}, v$lastStreamingDataRegister
                        iget-object v${scratchStart + 2}, p0, $videoIdField
                        move-object/16 v${scratchStart + 3}, v$adaptiveFormatsRegister

                        invoke-static/range { v$scratchStart .. v${scratchStart + 3} }, $EXTENSION_CLASS->diagnoseStreamingData(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/util/List;)V

                        invoke-static { v${scratchStart + 2}, v${scratchStart + 3} }, $EXTENSION_CLASS->hookAdaptiveFormats(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
                        move-result-object v${scratchStart + 3}
                        move-object/16 v$adaptiveFormatsRegister, v${scratchStart + 3}
                    """
                )

                // Save the actual StreamingData field value read by the first field access.
                addInstructionsAtControlFlowLabel(
                    firstStreamingDataIndex + 1,
                    "move-object/16 v$scratchStart, v$firstStreamingDataRegister"
                )
            }
        }

        adaptiveFormatMethod = WeakReference(HookAdaptiveFormatsFingerprint.method)
    }
}

internal fun hookAdaptiveFormat(descriptor: String) = adaptiveFormatMethod.get()!!.addInstructions(
    0,
    """
        invoke-static { p0, p1 }, $descriptor(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
        move-result-object p1
    """
)