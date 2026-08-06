package com.zin.jadxaimcp.utils;

import jadx.api.JavaMethod;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.MethodNode;

public class MethodSignatures {

    private MethodSignatures() {
    }

    /**
     * @param method The method to test against the caller supplied signature
     * @param methodSignature The signature/descriptor to match, e.g. '(I)V'
     * @return boolean True when the signature identifies this method
     *
     * MethodInfo builds its shortId once from the original method name and never
     * refreshes it, while JavaMethod.getName() reports the alias. After a rename the
     * two disagree, so a signature carrying the renamed name never matches the
     * shortId and the method looks missing. Both forms are compared here so a
     * signature keeps resolving before and after a rename.
     */
    public static boolean matches(JavaMethod method, String methodSignature) {
        if (methodSignature == null || methodSignature.isEmpty()) {
            return true;
        }

        MethodNode methodNode = method.getMethodNode();
        if (methodNode == null) {
            return false;
        }

        MethodInfo methodInfo = methodNode.getMethodInfo();
        if (methodInfo == null) {
            return false;
        }

        if (methodInfo.getShortId().contains(methodSignature)) {
            return true;
        }
        return methodInfo.makeSignature(true, true).contains(methodSignature);
    }
}
