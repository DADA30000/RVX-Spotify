package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {
    
    // 1. Core Premium Attributes Override (CRITICAL)
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, *> ?: return@after
                param.result = UnlockPremiumPatch.createOverriddenAttributesMap(result)
            }
        }
    }.onFailure { Logger.printException({ "Critical productStateProto hook failed" }, it) }

    // 2. Query parameters for artist page
    runCatching {
        ::buildQueryParametersFingerprint.hookMethod {
            after { param ->
                val result = param.result
                val FIELD = "checkDeviceCapability"
                if (result.toString().contains("${FIELD}=")) {
                    param.result = XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, arrayOf(param.args[0], true)
                    )
                }
            }
        }
    }.onFailure { Logger.printException({ "buildQueryParameters hook failed" }, it) }

    // 3. Google Assistant JSON parsing URI/URL sanitization
    runCatching {
        ::contextFromJsonFingerprint.hookMethod {
            fun removeStationString(field: Field, obj: Any) {
                field.set(obj, UnlockPremiumPatch.removeStationString(field.get(obj) as String))
            }

            after { param ->
                val thiz = param.result
                val clazz = param.result.javaClass
                removeStationString(clazz.findField("uri"), thiz)
                removeStationString(clazz.findField("url"), thiz)
            }
        }
    }.onFailure { Logger.printException({ "contextFromJson hook failed" }, it) }

    // 4. Force shuffle disable (Google Assistant commands)
    runCatching {
        XposedHelpers.findAndHookMethod(
            "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
            classLoader,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { Logger.printDebug { "PlayerOptionOverrides hook failed: ${it.message}" } }

    // 5. Context menu premium upsell filtering
    runCatching {
        val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
        XposedBridge.hookAllConstructors(
            contextMenuViewModelClazz, object : XC_MethodHook() {
                val isPremiumUpsell = ::isPremiumUpsellField.field

                override fun beforeHookedMethod(param: MethodHookParam) {
                    val parameterTypes = (param.method as Constructor<*>).parameterTypes
                    Logger.printDebug { "ContextMenuViewModel(${parameterTypes.joinToString(",") { it.name }})" }
                    for (i in 0 until param.args.size) {
                        if (parameterTypes[i].name != "java.util.List") continue
                        val original = param.args[i] as? List<*> ?: continue
                        Logger.printDebug { "List value type: ${original.firstOrNull()?.javaClass}" }
                        val filtered = original.filter {
                            it!!.callMethod("getViewModel").let { isPremiumUpsell.get(it) } != true
                        }
                        param.args[i] = filtered
                        Logger.printDebug { "Filtered ${original.size - filtered.size} context menu items." }
                    }
                }
            })
    }.onFailure { Logger.printException({ "contextMenuViewModel hook failed" }, it) }
}
