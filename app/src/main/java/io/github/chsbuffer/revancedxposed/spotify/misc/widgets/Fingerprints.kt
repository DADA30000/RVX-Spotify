package io.github.chsbuffer.revancedxposed.spotify.misc.widgets

import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.findMethodDirect
import io.github.chsbuffer.revancedxposed.fingerprint

val canBindAppWidgetPermissionFingerprint = findMethodDirect {
    runCatching {
        this.fingerprint {
            strings("android.permission.BIND_APPWIDGET")
            opcodes(Opcode.AND_INT_LIT8)
        }
    }.getOrElse {
        findMethod {
            matcher {
                returnType("boolean")
                usingStrings("MBS: Caller has more than one signature certificate!")
            }
        }.single()
    }
}