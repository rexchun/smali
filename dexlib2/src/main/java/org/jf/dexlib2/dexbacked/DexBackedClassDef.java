/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.dexbacked;

import com.google.common.collect.ImmutableList;
import org.jf.dexlib2.dexbacked.util.*;
import org.jf.dexlib2.iface.ClassDef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class DexBackedClassDef implements ClassDef {
    @Nonnull public final DexBuffer dexBuf;
    private final int classDefOffset;

    private int classDataOffset = -1;

    @Nullable private AnnotationsDirectory annotationsDirectory;

    //class_def_item offsets
    private static final int CLASS_NAME_OFFSET = 0;
    private static final int ACCESS_FLAGS_OFFSET = 4;
    private static final int SUPERCLASS_OFFSET = 8;
    private static final int INTERFACES_OFFSET = 12;
    private static final int SOURCE_FILE_OFFSET = 16;
    private static final int ANNOTATIONS_OFFSET = 20;
    private static final int CLASS_DATA_OFFSET = 24;
    private static final int STATIC_INITIAL_VALUES_OFFSET = 28;

    public DexBackedClassDef(@Nonnull DexBuffer dexBuf,
                             int classDefOffset) {
        this.dexBuf = dexBuf;
        this.classDefOffset = classDefOffset;
    }

    @Nonnull
    @Override
    public String getName() {
        return dexBuf.getType(dexBuf.readSmallUint(classDefOffset + CLASS_NAME_OFFSET));
    }

    @Nullable
    @Override
    public String getSuperclass() {
        return dexBuf.getOptionalType(dexBuf.readOptionalUint(classDefOffset + SUPERCLASS_OFFSET));
    }

    @Override
    public int getAccessFlags() {
        return dexBuf.readSmallUint(classDefOffset + ACCESS_FLAGS_OFFSET);
    }

    @Nullable
    @Override
    public String getSourceFile() {
        return dexBuf.getOptionalString(dexBuf.readOptionalUint(classDefOffset + SOURCE_FILE_OFFSET));
    }

    @Nonnull
    @Override
    public List<String> getInterfaces() {
        final int interfacesOffset = dexBuf.readSmallUint(classDefOffset + INTERFACES_OFFSET);
        if (interfacesOffset > 0) {
            final int size = dexBuf.readSmallUint(interfacesOffset);
            return new FixedSizeList<String>() {
                @Nonnull
                @Override
                public String readItem(int index) {
                    return dexBuf.getType(dexBuf.readUshort(interfacesOffset + 4 + (2*index)));
                }

                @Override public int size() { return size; }
            };
        }
        return ImmutableList.of();
    }

    @Nonnull
    @Override
    public List<? extends DexBackedAnnotation> getAnnotations() {
        return getAnnotationsDirectory().getClassAnnotations();
    }

    @Nonnull
    @Override
    public List<? extends DexBackedField> getFields() {
        int classDataOffset = getClassDataOffset();
        if (getClassDataOffset() != 0) {
            DexReader reader = dexBuf.readerAt(classDataOffset);
            final int staticFieldCount = reader.readSmallUleb128();
            int instanceFieldCount = reader.readSmallUleb128();
            final int fieldCount = staticFieldCount + instanceFieldCount;
            if (fieldCount > 0) {
                reader.skipUleb128(); //direct_methods_size
                reader.skipUleb128(); //virtual_methods_size

                final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
                final int staticInitialValuesOffset =
                        dexBuf.readSmallUint(classDefOffset + STATIC_INITIAL_VALUES_OFFSET);
                final int fieldsStartOffset = reader.getOffset();

                return new VariableSizeListWithContext<DexBackedField>() {
                    @Nonnull
                    @Override
                    public VariableSizeListIterator listIterator() {
                        return new VariableSizeListIterator(dexBuf, fieldsStartOffset) {
                            private int previousFieldIndex = 0;
                            @Nonnull private final AnnotationsDirectory.AnnotationIterator annotationIterator =
                                    annotationsDirectory.getFieldAnnotationIterator();
                            @Nonnull private final StaticInitialValueIterator staticInitialValueIterator =
                                    StaticInitialValueIterator.newOrEmpty(dexBuf, staticInitialValuesOffset);

                            @Nonnull
                            @Override
                            protected DexBackedField readItem(@Nonnull DexReader reader, int index) {
                                if (index == staticFieldCount) {
                                    // We reached the end of the static field, restart the numbering for
                                    // instance fields
                                    previousFieldIndex = 0;
                                    annotationIterator.reset();
                                }
                                DexBackedField item = new DexBackedField(reader, previousFieldIndex,
                                        staticInitialValueIterator, annotationIterator);
                                previousFieldIndex = item.fieldIndex;
                                return item;
                            }

                            @Override
                            protected void skipItem(@Nonnull DexReader reader, int index) {
                                if (index == staticFieldCount) {
                                    // We reached the end of the static field, restart the numbering for
                                    // instance fields
                                    previousFieldIndex = 0;
                                    annotationIterator.reset();
                                }
                                previousFieldIndex = DexBackedField.skipEncodedField(reader, previousFieldIndex);
                                staticInitialValueIterator.skipNext();
                            }
                        };
                    }

                    @Override public int size() { return fieldCount; }
                };
            }
        }
        return ImmutableList.of();
    }

    @Nonnull
    @Override
    public List<? extends DexBackedMethod> getMethods() {
        int classDataOffset = getClassDataOffset();
        if (classDataOffset > 0) {
            DexReader reader = dexBuf.readerAt(classDataOffset);
            int staticFieldCount = reader.readSmallUleb128();
            int instanceFieldCount = reader.readSmallUleb128();
            final int directMethodCount = reader.readSmallUleb128();
            int virtualMethodCount = reader.readSmallUleb128();
            final int methodCount = directMethodCount + virtualMethodCount;
            if (methodCount > 0) {
                DexBackedField.skipAllFields(reader, staticFieldCount + instanceFieldCount);

                final AnnotationsDirectory annotationsDirectory = getAnnotationsDirectory();
                final int methodsStartOffset = reader.getOffset();

                return new VariableSizeListWithContext<DexBackedMethod>() {
                    @Nonnull
                    @Override
                    public VariableSizeListIterator listIterator() {
                        return new VariableSizeListIterator(dexBuf, methodsStartOffset) {
                            private int previousMethodIndex = 0;
                            @Nonnull private final AnnotationsDirectory.AnnotationIterator methodAnnotationIterator =
                                    annotationsDirectory.getMethodAnnotationIterator();
                            @Nonnull private final AnnotationsDirectory.AnnotationIterator parameterAnnotationIterator =
                                    annotationsDirectory.getParameterAnnotationIterator();

                            @Nonnull
                            @Override
                            protected DexBackedMethod readItem(@Nonnull DexReader reader, int index) {
                                if (index == directMethodCount) {
                                    // We reached the end of the direct methods, restart the numbering for
                                    // virtual methods
                                    previousMethodIndex = 0;
                                    methodAnnotationIterator.reset();
                                    parameterAnnotationIterator.reset();
                                }
                                DexBackedMethod item = new DexBackedMethod(reader, DexBackedClassDef.this,
                                        previousMethodIndex, methodAnnotationIterator, parameterAnnotationIterator);
                                previousMethodIndex = item.methodIndex;
                                return item;
                            }

                            @Override
                            protected void skipItem(@Nonnull DexReader reader, int index) {
                                if (index == directMethodCount) {
                                    // We reached the end of the direct methods, restart the numbering for
                                    // virtual methods
                                    previousMethodIndex = 0;
                                    methodAnnotationIterator.reset();
                                    parameterAnnotationIterator.reset();
                                }
                                previousMethodIndex = DexBackedMethod.skipEncodedMethod(reader, previousMethodIndex);
                            }
                        };
                    }

                    @Override public int size() { return methodCount; }
                };
            }
        }
        return ImmutableList.of();
    }

    private int getClassDataOffset() {
        if (classDataOffset == -1) {
            classDataOffset = dexBuf.readSmallUint(classDefOffset + CLASS_DATA_OFFSET);
        }
        return classDataOffset;
    }

    private AnnotationsDirectory getAnnotationsDirectory() {
        if (annotationsDirectory == null) {
            int annotationsDirectoryOffset = dexBuf.readSmallUint(classDefOffset + ANNOTATIONS_OFFSET);
            annotationsDirectory = AnnotationsDirectory.newOrEmpty(dexBuf, annotationsDirectoryOffset);
        }
        return annotationsDirectory;
    }
}
