/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util.kotlin;

import kotlin.metadata.KmAnnotation;
import kotlin.metadata.KmProperty;
import kotlin.metadata.internal.extensions.*;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.internal.*;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/*
 * This is a fun meme. All of these kotlin classes are marked as "internal" so Kotlin code cannot compile against them.
 * However, luckily for us the Java compiler has no idea about this, so they can compile against it :D
 *
 * This file contains Java wrappers around Kotlin classes, to used by Kotlin.
 */
public interface JvmExtensionWrapper {
    static class Class implements JvmExtensionWrapper {
        private final JvmClassExtension extension;

        public Class(JvmClassExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static Class get(KmClassExtension classExtension) {
            if (classExtension instanceof JvmClassExtension) {
                return new Class((JvmClassExtension) classExtension);
            }

            return null;
        }

        public List<KmProperty> getLocalDelegatedProperties() {
            return extension.getLocalDelegatedProperties();
        }

        @Nullable
        public String getModuleName() {
            return extension.getModuleName();
        }

        public void setModuleName(@Nullable String name) {
            extension.setModuleName(name);
        }

        @Nullable
        public String getAnonymousObjectOriginName() {
            return extension.getAnonymousObjectOriginName();
        }

        public void setAnonymousObjectOriginName(@Nullable String name) {
            extension.setAnonymousObjectOriginName(name);
        }

        public int getJvmFlags() {
            return extension.getJvmFlags();
        }

        public void setJvmFlags(int flags) {
            extension.setJvmFlags(flags);
        }

        public JvmClassExtension getExtension() {
            return extension;
        }
    }

    static class Package {
        private final JvmPackageExtension extension;

        public Package(JvmPackageExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static Package get(KmPackageExtension packageExtension) {
            if (packageExtension instanceof JvmPackageExtension) {
                return new Package((JvmPackageExtension) packageExtension);
            }

            return null;
        }

        public List<KmProperty> getLocalDelegatedProperties() {
            return extension.getLocalDelegatedProperties();
        }

        @Nullable
        public String getModuleName() {
            return extension.getModuleName();
        }

        public void setModuleName(@Nullable String name) {
            extension.setModuleName(name);
        }

        public JvmPackageExtension getExtension() {
            return extension;
        }
    }

    static class Function {
        private final JvmFunctionExtension extension;

        public Function(JvmFunctionExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static Function get(KmFunctionExtension functionExtension) {
            if (functionExtension instanceof JvmFunctionExtension) {
                return new Function((JvmFunctionExtension) functionExtension);
            }

            return null;
        }

        @Nullable
        public JvmMethodSignature getSignature() {
            return extension.getSignature();
        }

        public void setSignature(@Nullable JvmMethodSignature signature) {
            extension.setSignature(signature);
        }

        @Nullable
        public String getLambdaClassOriginName() {
            return extension.getLambdaClassOriginName();
        }

        public void setLambdaClassOriginName(@Nullable String name) {
            extension.setLambdaClassOriginName(name);
        }

        public JvmFunctionExtension getExtension() {
            return extension;
        }
    }

    static class Property {
        private final JvmPropertyExtension extension;

        public Property(JvmPropertyExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static Property get(KmPropertyExtension propertyExtension) {
            if (propertyExtension instanceof JvmPropertyExtension) {
                return new Property((JvmPropertyExtension) propertyExtension);
            }

            return null;
        }

        public int getJvmFlags() {
            return extension.getJvmFlags();
        }

        public void setJvmFlags(int flags) {
            extension.setJvmFlags(flags);
        }

        @Nullable
        public JvmFieldSignature getFieldSignature() {
            return extension.getFieldSignature();
        }

        public void setFieldSignature(@Nullable JvmFieldSignature signature) {
            extension.setFieldSignature(signature);
        }

        @Nullable
        public JvmMethodSignature getGetterSignature() {
            return extension.getGetterSignature();
        }

        public void setGetterSignature(@Nullable JvmMethodSignature signature) {
            extension.setGetterSignature(signature);
        }

        @Nullable
        public JvmMethodSignature getSetterSignature() {
            return extension.getSetterSignature();
        }

        public void setSetterSignature(@Nullable JvmMethodSignature signature) {
            extension.setSetterSignature(signature);
        }

        @Nullable
        public JvmMethodSignature getSyntheticMethodForAnnotations() {
            return extension.getSyntheticMethodForAnnotations();
        }

        public void setSyntheticMethodForAnnotations(@Nullable JvmMethodSignature signature) {
            extension.setSyntheticMethodForAnnotations(signature);
        }

        @Nullable
        public JvmMethodSignature getSyntheticMethodForDelegate() {
            return extension.getSyntheticMethodForDelegate();
        }

        public void setSyntheticMethodForDelegate(@Nullable JvmMethodSignature signature) {
            extension.setSyntheticMethodForDelegate(signature);
        }

        public JvmPropertyExtension getExtension() {
            return extension;
        }
    }

    static class Constructor {
        private final JvmConstructorExtension extension;

        public Constructor(JvmConstructorExtension extension) {
            this.extension = extension;
        }


        @Nullable
        public static Constructor get(KmConstructorExtension constructorExtension) {
            if (constructorExtension instanceof JvmConstructorExtension) {
                return new Constructor((JvmConstructorExtension) constructorExtension);
            }

            return null;
        }

        @Nullable
        public JvmMethodSignature getSignature() {
            return extension.getSignature();
        }

        public void setSignature(@Nullable JvmMethodSignature signature) {
            extension.setSignature(signature);
        }

        public JvmConstructorExtension getExtension() {
            return extension;
        }
    }

//    record TypeParameter(JvmTypeParameterExtension extension) {
    static class TypeParameter {
        private final JvmTypeParameterExtension extension;

        public TypeParameter(JvmTypeParameterExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static TypeParameter get(KmTypeParameterExtension typeParameterExtension) {
            if (typeParameterExtension instanceof JvmTypeParameterExtension) {
                return new TypeParameter((JvmTypeParameterExtension) typeParameterExtension);
            }

            return null;
        }

        public List<KmAnnotation> getAnnotations() {
            return extension.getAnnotations();
        }

        public JvmTypeParameterExtension getExtension() {
            return extension;
        }
    }

//    record Type(JvmTypeExtension extension) {
    static class Type {
        private final JvmTypeExtension extension;

        public Type(JvmTypeExtension extension) {
            this.extension = extension;
        }

        @Nullable
        public static Type get(KmTypeExtension typeExtension) {
            if (typeExtension instanceof JvmTypeExtension) {
                return new Type((JvmTypeExtension) typeExtension);
            }

            return null;
        }

        public boolean isRaw() {
            return extension.isRaw();
        }

        public void setRaw(boolean raw) {
            extension.setRaw(raw);
        }

        public List<KmAnnotation> getAnnotations() {
            return extension.getAnnotations();
        }

        public JvmTypeExtension getExtension() {
            return extension;
        }
    }
}