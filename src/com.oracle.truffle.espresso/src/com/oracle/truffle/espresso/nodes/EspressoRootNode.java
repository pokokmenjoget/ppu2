/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.nodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.bytecode.*;
import com.oracle.truffle.espresso.classfile.*;
import com.oracle.truffle.espresso.impl.FieldInfo;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.MethodInfo;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.ExceptionHandler;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.*;
import com.oracle.truffle.espresso.vm.InterpreterToVM;
import com.oracle.truffle.object.DebugCounter;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Supplier;

import static com.oracle.truffle.espresso.bytecode.Bytecodes.*;
import static com.oracle.truffle.espresso.meta.Meta.meta;

public class EspressoRootNode extends RootNode implements LinkedNode {

    @Children private InvokeNode[] nodes = new InvokeNode[0];

    private final MethodInfo method;
    private final InterpreterToVM vm;

    private static final DebugCounter bytecodesExecuted = DebugCounter.create("Bytecodes executed");
    private static final DebugCounter newInstances = DebugCounter.create("New instances");
    private static final DebugCounter fieldWrites = DebugCounter.create("Field writes");
    private static final DebugCounter fieldReads = DebugCounter.create("Field reads");

    @CompilerDirectives.CompilationFinal(dimensions = 1) private final FrameSlot[] locals;

    private final BytecodeStream bs;

    @Override
    public String getName() {
        // TODO(peterssen): Set proper location.
        return getMethod().getDeclaringClass().getName() +
                        "." + getMethod().getName() +
                        " " + getMethod().getSignature().toString();
    }

    @CompilerDirectives.TruffleBoundary
    public EspressoRootNode(TruffleLanguage<EspressoContext> language, MethodInfo method, InterpreterToVM vm) {
        super(language, initFrameDescriptor(method));
        this.method = method;
        this.vm = vm;
        this.bs = new BytecodeStream(method.getCode());
        this.locals = getFrameDescriptor().getSlots().toArray(new FrameSlot[0]);
    }

    public MethodInfo getMethod() {
        return method;
    }

    @ExplodeLoop
    private static FrameDescriptor initFrameDescriptor(MethodInfo method) {
        FrameDescriptor descriptor = new FrameDescriptor();
        int maxLocals = method.getMaxLocals();
        for (int i = 0; i < maxLocals; ++i) {
            descriptor.addFrameSlot(i); // illegal by default
        }

        boolean hasReceiver = !method.isStatic();
        int argCount = method.getSignature().getParameterCount(false);
        FrameSlot[] locals = descriptor.getSlots().toArray(new FrameSlot[0]);

        int n = 0;
        if (hasReceiver) {
            descriptor.setFrameSlotKind(locals[0], FrameSlotKind.Object);
            n += JavaKind.Object.getSlotCount();
        }
        for (int i = 0; i < argCount; ++i) {
            JavaKind expectedkind = method.getSignature().getParameterKind(i);
            switch (expectedkind) {
                case Boolean:
                case Byte:
                case Short:
                case Char:
                case Int:
                    descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Int);
                    break;
                case Float:
                    descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Float);
                    break;
                case Long:
                    descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Long);
                    break;
                case Double:
                    descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Double);
                    break;
                case Object:
                    descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Object);
                    break;
                case Void:
                case Illegal:
                    CompilerDirectives.transferToInterpreter();
                    throw EspressoError.shouldNotReachHere();
            }
            n += expectedkind.getSlotCount();
        }

        return descriptor;
    }

    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.MERGE_EXPLODE)
    public Object execute(VirtualFrame frame) {
        int curBCI = 0;
        final OperandStack stack = new DualStack(method.getMaxStackSize());
        initArguments(frame);

        loop: while (true) {
            try {
                bytecodesExecuted.inc();

                CompilerAsserts.partialEvaluationConstant(bs.currentBC(curBCI));
                CompilerAsserts.partialEvaluationConstant(curBCI);

                switch (bs.currentBC(curBCI)) {
                    case NOP:
                        break;
                    case ACONST_NULL:
                        stack.pushObject(StaticObject.NULL);
                        break;
                    case ICONST_M1:
                        stack.pushInt(-1);
                        break;
                    case ICONST_0:
                        stack.pushInt(0);
                        break;
                    case ICONST_1:
                        stack.pushInt(1);
                        break;
                    case ICONST_2:
                        stack.pushInt(2);
                        break;
                    case ICONST_3:
                        stack.pushInt(3);
                        break;
                    case ICONST_4:
                        stack.pushInt(4);
                        break;
                    case ICONST_5:
                        stack.pushInt(5);
                        break;
                    case LCONST_0:
                        stack.pushLong(0L);
                        break;
                    case LCONST_1:
                        stack.pushLong(1L);
                        break;
                    case FCONST_0:
                        stack.pushFloat(0.0F);
                        break;
                    case FCONST_1:
                        stack.pushFloat(1.0F);
                        break;
                    case FCONST_2:
                        stack.pushFloat(2.0F);
                        break;
                    case DCONST_0:
                        stack.pushDouble(0.0D);
                        break;
                    case DCONST_1:
                        stack.pushDouble(1.0D);
                        break;
                    case BIPUSH:
                        stack.pushInt(bs.readByte(curBCI));
                        break;
                    case SIPUSH:
                        stack.pushInt(bs.readShort(curBCI));
                        break;
                    case LDC:
                    case LDC_W:
                    case LDC2_W:
                        pushPoolConstant(stack, bs.readCPI(curBCI));
                        break;
                    case ILOAD:
                        stack.pushInt(getIntLocal(frame, bs.readLocalIndex(curBCI)));
                        break;
                    case LLOAD:
                        stack.pushLong(getLongLocal(frame, bs.readLocalIndex(curBCI)));
                        break;
                    case FLOAD:
                        stack.pushFloat(getFloatLocal(frame, bs.readLocalIndex(curBCI)));
                        break;
                    case DLOAD:
                        stack.pushDouble(getDoubleLocal(frame, bs.readLocalIndex(curBCI)));
                        break;
                    case ALOAD:
                        stack.pushObject(getObjectLocal(frame, bs.readLocalIndex(curBCI)));
                        break;
                    case ILOAD_0:
                        stack.pushInt(getIntLocal(frame, 0));
                        break;
                    case ILOAD_1:
                        stack.pushInt(getIntLocal(frame, 1));
                        break;
                    case ILOAD_2:
                        stack.pushInt(getIntLocal(frame, 2));
                        break;
                    case ILOAD_3:
                        stack.pushInt(getIntLocal(frame, 3));
                        break;
                    case LLOAD_0:
                        stack.pushLong(getLongLocal(frame, 0));
                        break;
                    case LLOAD_1:
                        stack.pushLong(getLongLocal(frame, 1));
                        break;
                    case LLOAD_2:
                        stack.pushLong(getLongLocal(frame, 2));
                        break;
                    case LLOAD_3:
                        stack.pushLong(getLongLocal(frame, 3));
                        break;
                    case FLOAD_0:
                        stack.pushFloat(getFloatLocal(frame, 0));
                        break;
                    case FLOAD_1:
                        stack.pushFloat(getFloatLocal(frame, 1));
                        break;
                    case FLOAD_2:
                        stack.pushFloat(getFloatLocal(frame, 2));
                        break;
                    case FLOAD_3:
                        stack.pushFloat(getFloatLocal(frame, 3));
                        break;
                    case DLOAD_0:
                        stack.pushDouble(getDoubleLocal(frame, 0));
                        break;
                    case DLOAD_1:
                        stack.pushDouble(getDoubleLocal(frame, 1));
                        break;
                    case DLOAD_2:
                        stack.pushDouble(getDoubleLocal(frame, 2));
                        break;
                    case DLOAD_3:
                        stack.pushDouble(getDoubleLocal(frame, 3));
                        break;
                    case ALOAD_0:
                        stack.pushObject(getObjectLocal(frame, 0));
                        break;
                    case ALOAD_1:
                        stack.pushObject(getObjectLocal(frame, 1));
                        break;
                    case ALOAD_2:
                        stack.pushObject(getObjectLocal(frame, 2));
                        break;
                    case ALOAD_3:
                        stack.pushObject(getObjectLocal(frame, 3));
                        break;
                    case IALOAD:
                        stack.pushInt(vm.getArrayInt(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case LALOAD:
                        stack.pushLong(vm.getArrayLong(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case FALOAD:
                        stack.pushFloat(vm.getArrayFloat(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case DALOAD:
                        stack.pushDouble(vm.getArrayDouble(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case AALOAD:
                        stack.pushObject(vm.getArrayObject(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case BALOAD:
                        stack.pushInt(vm.getArrayByte(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case CALOAD:
                        stack.pushInt(vm.getArrayChar(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case SALOAD:
                        stack.pushInt(vm.getArrayShort(stack.popInt(), nullCheck(stack.popObject())));
                        break;
                    case ISTORE:
                        setIntLocal(frame, bs.readLocalIndex(curBCI), stack.popInt());
                        break;
                    case LSTORE:
                        setLongLocal(frame, bs.readLocalIndex(curBCI), stack.popLong());
                        break;
                    case FSTORE:
                        setFloatLocal(frame, bs.readLocalIndex(curBCI), stack.popFloat());
                        break;
                    case DSTORE:
                        setDoubleLocal(frame, bs.readLocalIndex(curBCI), stack.popDouble());
                        break;
                    case ASTORE:
                        setObjectLocal(frame, bs.readLocalIndex(curBCI), stack.popObject());
                        break;
                    case ISTORE_0:
                        setIntLocal(frame, 0, stack.popInt());
                        break;
                    case ISTORE_1:
                        setIntLocal(frame, 1, stack.popInt());
                        break;
                    case ISTORE_2:
                        setIntLocal(frame, 2, stack.popInt());
                        break;
                    case ISTORE_3:
                        setIntLocal(frame, 3, stack.popInt());
                        break;
                    case LSTORE_0:
                        setLongLocal(frame, 0, stack.popLong());
                        break;
                    case LSTORE_1:
                        setLongLocal(frame, 1, stack.popLong());
                        break;
                    case LSTORE_2:
                        setLongLocal(frame, 2, stack.popLong());
                        break;
                    case LSTORE_3:
                        setLongLocal(frame, 3, stack.popLong());
                        break;
                    case FSTORE_0:
                        setFloatLocal(frame, 0, stack.popFloat());
                        break;
                    case FSTORE_1:
                        setFloatLocal(frame, 1, stack.popFloat());
                        break;
                    case FSTORE_2:
                        setFloatLocal(frame, 2, stack.popFloat());
                        break;
                    case FSTORE_3:
                        setFloatLocal(frame, 3, stack.popFloat());
                        break;
                    case DSTORE_0:
                        setDoubleLocal(frame, 0, stack.popDouble());
                        break;
                    case DSTORE_1:
                        setDoubleLocal(frame, 1, stack.popDouble());
                        break;
                    case DSTORE_2:
                        setDoubleLocal(frame, 2, stack.popDouble());
                        break;
                    case DSTORE_3:
                        setDoubleLocal(frame, 3, stack.popDouble());
                        break;
                    case ASTORE_0:
                        setObjectLocal(frame, 0, stack.popObject());
                        break;
                    case ASTORE_1:
                        setObjectLocal(frame, 1, stack.popObject());
                        break;
                    case ASTORE_2:
                        setObjectLocal(frame, 2, stack.popObject());
                        break;
                    case ASTORE_3:
                        setObjectLocal(frame, 3, stack.popObject());
                        break;
                    case IASTORE:
                        vm.setArrayInt(stack.popInt(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case LASTORE:
                        vm.setArrayLong(stack.popLong(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case FASTORE:
                        vm.setArrayFloat(stack.popFloat(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case DASTORE:
                        vm.setArrayDouble(stack.popDouble(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case AASTORE:
                        vm.setArrayObject(stack.popObject(), stack.popInt(), (StaticObjectArray) nullCheck(stack.popObject()));
                        break;
                    case BASTORE:
                        vm.setArrayByte((byte) stack.popInt(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case CASTORE:
                        vm.setArrayChar((char) stack.popInt(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case SASTORE:
                        vm.setArrayShort((short) stack.popInt(), stack.popInt(), nullCheck(stack.popObject()));
                        break;
                    case POP:
                        stack.popVoid(1);
                        break;
                    case POP2:
                        stack.popVoid(2);
                        break;
                    case DUP:
                        stack.dup1();
                        break;
                    case DUP_X1:
                        stack.dupx1();
                        break;
                    case DUP_X2:
                        stack.dupx2();
                        break;
                    case DUP2:
                        stack.dup2();
                        break;
                    case DUP2_X1:
                        stack.dup2x1();
                        break;
                    case DUP2_X2:
                        stack.dup2x2();
                        break;
                    case SWAP:
                        stack.swapSingle();
                        break;
                    case IADD:
                        stack.pushInt(stack.popInt() + stack.popInt());
                        break;
                    case LADD:
                        stack.pushLong(stack.popLong() + stack.popLong());
                        break;
                    case FADD:
                        stack.pushFloat(stack.popFloat() + stack.popFloat());
                        break;
                    case DADD:
                        stack.pushDouble(stack.popDouble() + stack.popDouble());
                        break;
                    case ISUB:
                        stack.pushInt(-stack.popInt() + stack.popInt());
                        break;
                    case LSUB:
                        stack.pushLong(-stack.popLong() + stack.popLong());
                        break;
                    case FSUB:
                        stack.pushFloat(-stack.popFloat() + stack.popFloat());
                        break;
                    case DSUB:
                        stack.pushDouble(-stack.popDouble() + stack.popDouble());
                        break;
                    case IMUL:
                        stack.pushInt(stack.popInt() * stack.popInt());
                        break;
                    case LMUL:
                        stack.pushLong(stack.popLong() * stack.popLong());
                        break;
                    case FMUL:
                        stack.pushFloat(stack.popFloat() * stack.popFloat());
                        break;
                    case DMUL:
                        stack.pushDouble(stack.popDouble() * stack.popDouble());
                        break;
                    case IDIV:
                        stack.pushInt(divInt(checkNonZero(stack.popInt()), stack.popInt()));
                        break;
                    case LDIV:
                        stack.pushLong(divLong(checkNonZero(stack.popLong()), stack.popLong()));
                        break;
                    case FDIV:
                        stack.pushFloat(divFloat(stack.popFloat(), stack.popFloat()));
                        break;
                    case DDIV:
                        stack.pushDouble(divDouble(stack.popDouble(), stack.popDouble()));
                        break;
                    case IREM:
                        stack.pushInt(remInt(checkNonZero(stack.popInt()), stack.popInt()));
                        break;
                    case LREM:
                        stack.pushLong(remLong(checkNonZero(stack.popLong()), stack.popLong()));
                        break;
                    case FREM:
                        stack.pushFloat(remFloat(stack.popFloat(), stack.popFloat()));
                        break;
                    case DREM:
                        stack.pushDouble(remDouble(stack.popDouble(), stack.popDouble()));
                        break;
                    case INEG:
                        stack.pushInt(-stack.popInt());
                        break;
                    case LNEG:
                        stack.pushLong(-stack.popLong());
                        break;
                    case FNEG:
                        stack.pushFloat(-stack.popFloat());
                        break;
                    case DNEG:
                        stack.pushDouble(-stack.popDouble());
                        break;
                    case ISHL:
                        stack.pushInt(shiftLeftInt(stack.popInt(), stack.popInt()));
                        break;
                    case LSHL:
                        stack.pushLong(shiftLeftLong(stack.popInt(), stack.popLong()));
                        break;
                    case ISHR:
                        stack.pushInt(shiftRightSignedInt(stack.popInt(), stack.popInt()));
                        break;
                    case LSHR:
                        stack.pushLong(shiftRightSignedLong(stack.popInt(), stack.popLong()));
                        break;
                    case IUSHR:
                        stack.pushInt(shiftRightUnsignedInt(stack.popInt(), stack.popInt()));
                        break;
                    case LUSHR:
                        stack.pushLong(shiftRightUnsignedLong(stack.popInt(), stack.popLong()));
                        break;
                    case IAND:
                        stack.pushInt(stack.popInt() & stack.popInt());
                        break;
                    case LAND:
                        stack.pushLong(stack.popLong() & stack.popLong());
                        break;
                    case IOR:
                        stack.pushInt(stack.popInt() | stack.popInt());
                        break;
                    case LOR:
                        stack.pushLong(stack.popLong() | stack.popLong());
                        break;
                    case IXOR:
                        stack.pushInt(stack.popInt() ^ stack.popInt());
                        break;
                    case LXOR:
                        stack.pushLong(stack.popLong() ^ stack.popLong());
                        break;
                    case IINC:
                        setIntLocal(frame, bs.readLocalIndex(curBCI), getIntLocal(frame, bs.readLocalIndex(curBCI)) + bs.readIncrement(curBCI));
                        break;
                    case I2L:
                        stack.pushLong(stack.popInt());
                        break;
                    case I2F:
                        stack.pushFloat(stack.popInt());
                        break;
                    case I2D:
                        stack.pushDouble(stack.popInt());
                        break;
                    case L2I:
                        stack.pushInt((int) stack.popLong());
                        break;
                    case L2F:
                        stack.pushFloat(stack.popLong());
                        break;
                    case L2D:
                        stack.pushDouble(stack.popLong());
                        break;
                    case F2I:
                        stack.pushInt((int) stack.popFloat());
                        break;
                    case F2L:
                        stack.pushLong((long) stack.popFloat());
                        break;
                    case F2D:
                        stack.pushDouble(stack.popFloat());
                        break;
                    case D2I:
                        stack.pushInt((int) stack.popDouble());
                        break;
                    case D2L:
                        stack.pushLong((long) stack.popDouble());
                        break;
                    case D2F:
                        stack.pushFloat((float) stack.popDouble());
                        break;
                    case I2B:
                        stack.pushInt((byte) stack.popInt());
                        break;
                    case I2C:
                        stack.pushInt((char) stack.popInt());
                        break;
                    case I2S:
                        stack.pushInt((short) stack.popInt());
                        break;
                    case LCMP:
                        stack.pushInt(compareLong(stack.popLong(), stack.popLong()));
                        break;
                    case FCMPL:
                        stack.pushInt(compareFloatLess(stack.popFloat(), stack.popFloat()));
                        break;
                    case FCMPG:
                        stack.pushInt(compareFloatGreater(stack.popFloat(), stack.popFloat()));
                        break;
                    case DCMPL:
                        stack.pushInt(compareDoubleLess(stack.popDouble(), stack.popDouble()));
                        break;
                    case DCMPG:
                        stack.pushInt(compareDoubleGreater(stack.popDouble(), stack.popDouble()));
                        break;
                    case IFEQ:
                        if (stack.popInt() == 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFNE:
                        if (stack.popInt() != 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFLT:
                        if (stack.popInt() < 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFGE:
                        if (stack.popInt() >= 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFGT:
                        if (stack.popInt() > 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFLE:
                        if (stack.popInt() <= 0) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPEQ:
                        if (stack.popInt() == stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPNE:
                        if (stack.popInt() != stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPLT:
                        if (stack.popInt() > stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPGE:
                        if (stack.popInt() <= stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPGT:
                        if (stack.popInt() < stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ICMPLE:
                        if (stack.popInt() >= stack.popInt()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ACMPEQ:
                        if (stack.popObject() == stack.popObject()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IF_ACMPNE:
                        if (stack.popObject() != stack.popObject()) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case GOTO:
                    case GOTO_W:
                        curBCI = bs.readBranchDest(curBCI);
                        continue loop;
                    case JSR:
                    case JSR_W:
                        stack.pushReturnAddress(bs.nextBCI(curBCI));
                        curBCI = bs.readBranchDest(curBCI);
                        continue loop;
                    case RET:
                        curBCI = getReturnAddressLocal(frame, bs.readLocalIndex(curBCI));
                        continue loop;
                    case TABLESWITCH: {
                        // curBCI = tableSwitch(bs, curBCI, stack.popInt());
                        int index = stack.popInt();
                        BytecodeTableSwitch switchHelper = bs.getBytecodeTableSwitch();
                        int low = switchHelper.lowKey(curBCI);
                        CompilerAsserts.partialEvaluationConstant(low);
                        int high = switchHelper.highKey(curBCI);
                        CompilerAsserts.partialEvaluationConstant(high);
                        CompilerAsserts.partialEvaluationConstant(switchHelper);
                        assert low <= high;

                        // Interpreter uses direct lookup.
                        if (CompilerDirectives.inInterpreter()) {
                            if (low <= index && index <= high) {
                                curBCI = switchHelper.targetAt(curBCI, index - low);
                            } else {
                                curBCI = switchHelper.defaultTarget(curBCI);
                            }
                            continue loop;
                        }

                        for (int i = low; i <= high; ++i) {
                            if (i == index) {
                                CompilerAsserts.partialEvaluationConstant(i);
                                CompilerAsserts.partialEvaluationConstant(i - low);
                                CompilerAsserts.partialEvaluationConstant(switchHelper.targetAt(curBCI, i - low));
                                curBCI = switchHelper.targetAt(curBCI, i - low);
                                continue loop;
                            }
                        }

                        CompilerAsserts.partialEvaluationConstant(switchHelper.defaultTarget(curBCI));
                        curBCI = switchHelper.defaultTarget(curBCI);
                        continue loop;
                    }
                    case LOOKUPSWITCH: {
                        // curBCI = lookupSwitch(bs, curBCI, stack.popInt());
                        int key = stack.popInt();
                        BytecodeLookupSwitch switchHelper = bs.getBytecodeLookupSwitch();
                        int low = 0;
                        int high = switchHelper.numberOfCases(curBCI) - 1;
                        CompilerAsserts.partialEvaluationConstant(switchHelper);
                        while (low <= high) {
                            int mid = (low + high) >>> 1;
                            int midVal = switchHelper.keyAt(curBCI, mid);

                            if (midVal < key) {
                                low = mid + 1;
                            } else if (midVal > key) {
                                high = mid - 1;
                            } else {
                                curBCI = curBCI + switchHelper.offsetAt(curBCI, mid); // key found.
                                continue loop;
                            }
                        }
                        curBCI = switchHelper.defaultTarget(curBCI); // key not found.
                        continue loop;
                    }
                    case IRETURN:
                        return exitMethodAndReturn(stack.popInt());
                    case LRETURN:
                        return exitMethodAndReturnObject(stack.popLong());
                    case FRETURN:
                        return exitMethodAndReturnObject(stack.popFloat());
                    case DRETURN:
                        return exitMethodAndReturnObject(stack.popDouble());
                    case ARETURN:
                        return exitMethodAndReturnObject(stack.popObject());
                    case RETURN:
                        return exitMethodAndReturn();
                    case GETSTATIC:
                        getField(stack, resolveField(bs.currentBC(curBCI), bs.readCPI(curBCI)), true);
                        break;
                    case PUTSTATIC:
                        putField(stack, resolveField(bs.currentBC(curBCI), bs.readCPI(curBCI)), true);
                        break;
                    case GETFIELD:
                        getField(stack, resolveField(bs.currentBC(curBCI), bs.readCPI(curBCI)), false);
                        break;
                    case PUTFIELD:
                        putField(stack, resolveField(bs.currentBC(curBCI), bs.readCPI(curBCI)), false);
                        break;
                    case INVOKEVIRTUAL:
                        quickenAndCallInvokeVirtual(stack, curBCI, resolveMethod(bs.currentBC(curBCI), bs.readCPI(curBCI)));
                        break;
                    case INVOKESPECIAL:
                        quickenAndCallInvokeSpecial(stack, curBCI, resolveMethod(bs.currentBC(curBCI), bs.readCPI(curBCI)));
                        break;
                    case INVOKESTATIC:
                        quickenAndCallInvokeStatic(stack, curBCI, resolveMethod(bs.currentBC(curBCI), bs.readCPI(curBCI)));
                        break;
                    case INVOKEINTERFACE:
                        quickenAndCallInvokeInterface(stack, curBCI, resolveMethod(bs.currentBC(curBCI), bs.readCPI(curBCI)));
                        break;
                    case NEW:
                        newInstances.inc();
                        stack.pushObject(allocateInstance(resolveType(bs.currentBC(curBCI), bs.readCPI(curBCI))));
                        break;
                    case NEWARRAY:
                        newInstances.inc();
                        stack.pushObject(InterpreterToVM.allocatePrimitiveArray(bs.readByte(curBCI), stack.popInt()));
                        break;
                    case ANEWARRAY:
                        newInstances.inc();
                        stack.pushObject(
                                        allocateArray(resolveType(bs.currentBC(curBCI), bs.readCPI(curBCI)), stack.popInt()));
                        break;
                    case ARRAYLENGTH:
                        stack.pushInt(vm.arrayLength(nullCheck(stack.popObject())));
                        break;
                    case ATHROW:
                        CompilerDirectives.transferToInterpreter();
                        throw new EspressoException(nullCheck(stack.popObject()));
                    case CHECKCAST:
                        stack.pushObject(checkCast(stack.popObject(), resolveType(bs.currentBC(curBCI), bs.readCPI(curBCI))));
                        break;
                    case INSTANCEOF:
                        stack.pushInt(
                                        instanceOf(stack.popObject(), resolveType(bs.currentBC(curBCI), bs.readCPI(curBCI))) ? 1 : 0);
                        break;
                    case MONITORENTER:
                        vm.monitorEnter(nullCheck(stack.popObject()));
                        break;
                    case MONITOREXIT:
                        vm.monitorExit(nullCheck(stack.popObject()));
                        break;
                    case WIDE:
                        // should not get here ByteCodeStream.currentBC() should never return this
                        // bytecode.
                        throw EspressoError.shouldNotReachHere();
                    case MULTIANEWARRAY:
                        newInstances.inc();
                        stack.pushObject(
                                        allocateMultiArray(stack,
                                                        resolveType(bs.currentBC(curBCI), bs.readCPI(curBCI)),
                                                        bs.readUByte(curBCI + 3)));
                        break;
                    case IFNULL:
                        if (StaticObject.isNull(stack.popObject())) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case IFNONNULL:
                        if (StaticObject.notNull(stack.popObject())) {
                            curBCI = bs.readBranchDest(curBCI);
                            continue loop;
                        }
                        break;
                    case BREAKPOINT:
                        CompilerDirectives.transferToInterpreter();
                        throw new UnsupportedOperationException("breakpoints not supported.");
                    case INVOKEDYNAMIC:
                        CompilerDirectives.transferToInterpreter();
                        throw new UnsupportedOperationException("invokedynamic not supported.");

                    case QUICK_INVOKESPECIAL:
                    case QUICK_INVOKESTATIC:
                    case QUICK_INVOKEVIRTUAL:
                    case QUICK_INVOKEINTERFACE:
                        nodes[bs.readCPI(curBCI)].invoke(stack);
                        break;
                }
            } catch (EspressoException e) {
                CompilerDirectives.transferToInterpreter();
                ExceptionHandler handler = resolveExceptionHandlers(curBCI, e.getException());
                if (handler != null) {
                    stack.clear();
                    stack.pushObject(e.getException());
                    curBCI = handler.getHandlerBCI();
                    continue loop; // skip bs.next()
                } else {
                    throw e;
                }
            } catch (VirtualMachineError e) {
                // TODO(peterssen): Host should not throw invalid VME (not in the boot classpath).
                CompilerDirectives.transferToInterpreter();
                Meta meta = EspressoLanguage.getCurrentContext().getMeta();
                StaticObject ex = meta.initEx(e.getClass());
                ExceptionHandler handler = resolveExceptionHandlers(curBCI, ex);
                if (handler != null) {
                    stack.clear();
                    stack.pushObject(ex);
                    curBCI = handler.getHandlerBCI();
                    continue loop; // skip bs.next()
                } else {
                    throw new EspressoException(ex);
                }
            }
            curBCI = bs.next(curBCI);
        }
    }

    private char addInvokeNode(InvokeNode node) {
        CompilerAsserts.neverPartOfCompilation();
        nodes = Arrays.copyOf(nodes, nodes.length + 1);
        int nodeIndex = nodes.length - 1; // latest empty slot
        nodes[nodeIndex] = insert(node);
        return (char) nodeIndex;
    }

    void patchBci(int bci, byte opcode, char nodeIndex) {
        assert Bytecodes.isQuickened(opcode);
        byte[] code = getMethod().getCode();
        code[bci] = opcode;
        code[bci + 1] = (byte) ((nodeIndex >> 8) & 0xFF);
        code[bci + 2] = (byte) ((nodeIndex) & 0xFF);
    }

    private void quickenAndCallInvokeStatic(OperandStack stack, int curBCI, MethodInfo resolvedMethod) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        int nodeIndex = addInvokeNode(new InvokeStaticNode(resolvedMethod));
        patchBci(curBCI, (byte) QUICK_INVOKESTATIC, (char) nodeIndex);
        nodes[nodeIndex].invoke(stack);
    }

    private void quickenAndCallInvokeSpecial(OperandStack stack, int curBCI, MethodInfo resolvedMethod) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        int nodeIndex = addInvokeNode(new InvokeSpecialNode(resolvedMethod));
        patchBci(curBCI, (byte) QUICK_INVOKESPECIAL, (char) nodeIndex);
        nodes[nodeIndex].invoke(stack);
    }

    private void quickenAndCallInvokeInterface(OperandStack stack, int curBCI, MethodInfo resolutionSeed) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        int nodeIndex = addInvokeNode(InvokeInterfaceNodeGen.create(resolutionSeed));
        patchBci(curBCI, (byte) QUICK_INVOKEINTERFACE, (char) nodeIndex);
        nodes[nodeIndex].invoke(stack);
    }

    // TODO(peterssen): Remove duplicated methods.
    private void quickenAndCallInvokeVirtual(OperandStack stack, int curBCI, MethodInfo resolutionSeed) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (resolutionSeed.isFinal() || resolutionSeed.getDeclaringClass().isFinalFlagSet()) {
            quickenAndCallInvokeSpecial(stack, curBCI, resolutionSeed);
            return;
        }
        int nodeIndex = addInvokeNode(InvokeVirtualNodeGen.create(resolutionSeed));
        patchBci(curBCI, (byte) QUICK_INVOKEVIRTUAL, (char) nodeIndex);
        nodes[nodeIndex].invoke(stack);
    }

    @ExplodeLoop
    private ExceptionHandler resolveExceptionHandlers(int bci, StaticObject ex) {
        CompilerAsserts.partialEvaluationConstant(bci);
        ExceptionHandler[] handlers = getMethod().getExceptionHandlers();
        for (ExceptionHandler handler : handlers) {
            if (bci >= handler.getStartBCI() && bci < handler.getEndBCI()) {
                Klass catchType = null;
                if (!handler.isCatchAll()) {
                    // exception handlers are similar to instanceof bytecodes, so we pass instanceof
                    catchType = resolveType(Bytecodes.INSTANCEOF, (char) handler.catchTypeCPI());
                }
                if (catchType == null || vm.instanceOf(ex, catchType)) {
                    // the first found exception handler is our exception handler
                    return handler;
                }
            }
        }
        return null;
    }

    void setIntLocal(VirtualFrame frame, int n, int value) {
        FrameDescriptor descriptor = getFrameDescriptor();
        if (descriptor.getFrameSlotKind(locals[n]) != FrameSlotKind.Int) {
            descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Int);
        }
        frame.setInt(locals[n], value);
    }

    void setFloatLocal(VirtualFrame frame, int n, float value) {
        FrameDescriptor descriptor = getFrameDescriptor();
        if (descriptor.getFrameSlotKind(locals[n]) != FrameSlotKind.Float) {
            descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Float);
        }
        frame.setFloat(locals[n], value);
    }

    void setDoubleLocal(VirtualFrame frame, int n, double value) {
        FrameDescriptor descriptor = getFrameDescriptor();
        if (descriptor.getFrameSlotKind(locals[n]) != FrameSlotKind.Double) {
            descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Double);
        }
        frame.setDouble(locals[n], value);
    }

    void setLongLocal(VirtualFrame frame, int n, long value) {
        FrameDescriptor descriptor = getFrameDescriptor();
        if (descriptor.getFrameSlotKind(locals[n]) != FrameSlotKind.Long) {
            descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Long);
        }
        frame.setLong(locals[n], value);
    }

    void setObjectLocal(VirtualFrame frame, int n, Object value) {
        FrameDescriptor descriptor = getFrameDescriptor();
        if (descriptor.getFrameSlotKind(locals[n]) != FrameSlotKind.Object) {
            descriptor.setFrameSlotKind(locals[n], FrameSlotKind.Object);
        }
        frame.setObject(locals[n], value);
    }

    int getIntLocal(VirtualFrame frame, int n) {
        return FrameUtil.getIntSafe(frame, locals[n]);
    }

    float getFloatLocal(VirtualFrame frame, int n) {
        return FrameUtil.getFloatSafe(frame, locals[n]);
    }

    double getDoubleLocal(VirtualFrame frame, int n) {
        return FrameUtil.getDoubleSafe(frame, locals[n]);
    }

    long getLongLocal(VirtualFrame frame, int n) {
        return FrameUtil.getLongSafe(frame, locals[n]);
    }

    StaticObject getObjectLocal(VirtualFrame frame, int n) {
        Object result = FrameUtil.getObjectSafe(frame, locals[n]);
        assert !(result instanceof ReturnAddress) : "use getReturnAddressLocal";
        return (StaticObject) result;
    }

    int getReturnAddressLocal(VirtualFrame frame, int n) {
        return ((ReturnAddress) FrameUtil.getObjectSafe(frame, locals[n])).getBci();
    }

    @ExplodeLoop
    private static Object[] copyOfRange(Object[] src, int from, int toExclusive) {
        int len = toExclusive - from;
        Object[] dst = new Object[len];
        for (int i = 0; i < len; ++i) {
            dst[i] = src[i + from];
        }
        return dst;
    }

    @ExplodeLoop
    private void initArguments(final VirtualFrame frame) {
        boolean hasReceiver = !getMethod().isStatic();
        int argCount = method.getSignature().getParameterCount(false);

        CompilerAsserts.partialEvaluationConstant(argCount);
        CompilerAsserts.partialEvaluationConstant(locals.length);

        Object[] frameArguments = frame.getArguments();
        Object[] arguments;
        if (hasReceiver) {
            arguments = copyOfRange(frameArguments, 1, argCount + 1);
        } else {
            arguments = frameArguments;
        }

        assert arguments.length == argCount;

        int n = 0;
        if (hasReceiver) {
            setObjectLocal(frame, n, frameArguments[0]);
            n += JavaKind.Object.getSlotCount();
        }
        for (int i = 0; i < argCount; ++i) {
            JavaKind expectedkind = method.getSignature().getParameterKind(i);
            switch (expectedkind) {
                case Boolean:
                    setIntLocal(frame, n, ((boolean) arguments[i]) ? 1 : 0);
                    break;
                case Byte:
                    setIntLocal(frame, n, ((byte) arguments[i]));
                    break;
                case Short:
                    setIntLocal(frame, n, ((short) arguments[i]));
                    break;
                case Char:
                    setIntLocal(frame, n, ((char) arguments[i]));
                    break;
                case Int:
                    setIntLocal(frame, n, (int) arguments[i]);
                    break;
                case Float:
                    setFloatLocal(frame, n, (float) arguments[i]);
                    break;
                case Long:
                    setLongLocal(frame, n, (long) arguments[i]);
                    break;
                case Double:
                    setDoubleLocal(frame, n, (double) arguments[i]);
                    break;
                case Object:
                    setObjectLocal(frame, n, arguments[i]);
                    break;
                case Void:
                case Illegal:
                    CompilerDirectives.transferToInterpreter();
                    throw EspressoError.shouldNotReachHere();
            }
            n += expectedkind.getSlotCount();
        }
    }

    private MethodInfo resolveMethod(int opcode, char cpi) {
        CompilerAsserts.partialEvaluationConstant(cpi);
        CompilerAsserts.partialEvaluationConstant(opcode);
        ConstantPool pool = getConstantPool();
        MethodInfo methodInfo = pool.methodAt(cpi).resolve(pool, cpi);
        return methodInfo;
    }

    @CompilerDirectives.TruffleBoundary
    private StaticObjectArray allocateArray(Klass componentType, int length) {
        assert !componentType.isPrimitive();
        return vm.newArray(componentType, length);
    }

    @CompilerDirectives.TruffleBoundary
    private StaticObject allocateMultiArray(OperandStack stack, Klass klass, int allocatedDimensions) {
        assert klass.isArray();
        int[] dimensions = new int[allocatedDimensions];
        for (int i = allocatedDimensions - 1; i >= 0; i--) {
            dimensions[i] = stack.popInt();
        }
        return vm.newMultiArray(klass, dimensions);
    }

    private void pushPoolConstant(OperandStack stack, char cpi) {
        ConstantPool pool = getConstantPool();
        PoolConstant constant = pool.at(cpi);
        if (constant instanceof IntegerConstant) {
            stack.pushInt(((IntegerConstant) constant).value());
        } else if (constant instanceof LongConstant) {
            stack.pushLong(((LongConstant) constant).value());
        } else if (constant instanceof DoubleConstant) {
            stack.pushDouble(((DoubleConstant) constant).value());
        } else if (constant instanceof FloatConstant) {
            stack.pushFloat(((FloatConstant) constant).value());
        } else if (constant instanceof StringConstant) {
            // TODO(peterssen): Must be interned once, on creation.
            stack.pushObject(((StringConstant) constant).intern(pool));
        } else if (constant instanceof ClassConstant) {
            Klass klass = ((ClassConstant) constant).resolve(getConstantPool(), cpi);
            stack.pushObject(klass.mirror());
        }
    }

    private ConstantPool getConstantPool() {
        return this.method.getConstantPool();
    }

    private boolean instanceOf(StaticObject instance, Klass typeToCheck) {
        return vm.instanceOf(instance, typeToCheck);
    }

    private Object exitMethodAndReturn(int result) {
        switch (method.getReturnType().getJavaKind()) {
            case Boolean:
                return result != 0;
            case Byte:
                return (byte) result;
            case Short:
                return (short) result;
            case Char:
                return (char) result;
            case Int:
                return result;
            default:
                CompilerDirectives.transferToInterpreter();
                throw EspressoError.shouldNotReachHere();
        }
    }

    private static Object exitMethodAndReturnObject(Object result) {
        return result;
    }

    private static Object exitMethodAndReturn() {
        return exitMethodAndReturnObject(StaticObject.VOID);
    }

    private static int divInt(int divisor, int dividend) {
        return dividend / divisor;
    }

    private static long divLong(long divisor, long dividend) {
        return dividend / divisor;
    }

    private static float divFloat(float divisor, float dividend) {
        return dividend / divisor;
    }

    private static double divDouble(double divisor, double dividend) {
        return dividend / divisor;
    }

    private static int remInt(int divisor, int dividend) {
        return dividend % divisor;
    }

    private static long remLong(long divisor, long dividend) {
        return dividend % divisor;
    }

    private static float remFloat(float divisor, float dividend) {
        return dividend % divisor;
    }

    private static double remDouble(double divisor, double dividend) {
        return dividend % divisor;
    }

    private static int shiftLeftInt(int bits, int value) {
        return value << bits;
    }

    private static long shiftLeftLong(int bits, long value) {
        return value << bits;
    }

    private static int shiftRightSignedInt(int bits, int value) {
        return value >> bits;
    }

    private static long shiftRightSignedLong(int bits, long value) {
        return value >> bits;
    }

    private static int shiftRightUnsignedInt(int bits, int value) {
        return value >>> bits;
    }

    private static long shiftRightUnsignedLong(int bits, long value) {
        return value >>> bits;
    }

    /**
     * Binary search implementation for the lookup switch.
     */
    @SuppressWarnings("unused")
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_EXPLODE)
    private static int lookupSearch(BytecodeStream bs, int curBCI, int key) {
        BytecodeLookupSwitch switchHelper = bs.getBytecodeLookupSwitch();
        int low = 0;
        int high = switchHelper.numberOfCases(curBCI) - 1;
        CompilerAsserts.partialEvaluationConstant(switchHelper);
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midVal = switchHelper.keyAt(curBCI, mid);

            if (midVal < key) {
                low = mid + 1;
            } else if (midVal > key) {
                high = mid - 1;
            } else {
                return curBCI + switchHelper.offsetAt(curBCI, mid); // key found.
            }
        }
        return switchHelper.defaultTarget(curBCI); // key not found.
    }

    /**
     * The table switch lookup can be efficiently implemented using a constant lookup. It was
     * intentionally replaced by a linear search to help partial evaluation infer control flow
     * structure correctly.
     */
    @SuppressWarnings("unused")
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    private static int tableSwitch(BytecodeStream bs, int curBCI, int index) {
        BytecodeTableSwitch switchHelper = bs.getBytecodeTableSwitch();
        int low = switchHelper.lowKey(curBCI);
        CompilerAsserts.partialEvaluationConstant(low);
        int high = switchHelper.highKey(curBCI);
        CompilerAsserts.partialEvaluationConstant(high);
        CompilerAsserts.partialEvaluationConstant(switchHelper);
        assert low <= high;

        // Interpreter uses direct lookup.
        if (CompilerDirectives.inInterpreter()) {
            if (low <= index && index <= high) {
                return switchHelper.targetAt(curBCI, index - low);
            } else {
                return switchHelper.defaultTarget(curBCI);
            }
        }

        for (int i = low; i <= high; ++i) {
            if (i == index) {
                CompilerAsserts.partialEvaluationConstant(i);
                CompilerAsserts.partialEvaluationConstant(i - low);
                CompilerAsserts.partialEvaluationConstant(switchHelper.targetAt(curBCI, i - low));
                return switchHelper.targetAt(curBCI, i - low);
            }
        }

        CompilerAsserts.partialEvaluationConstant(switchHelper.defaultTarget(curBCI));
        return switchHelper.defaultTarget(curBCI);
    }

    private StaticObject checkCast(StaticObject instance, Klass typeToCheck) {
        return vm.checkCast(instance, typeToCheck);
    }

    private StaticObject allocateInstance(Klass klass) {
        klass.initialize();
        return vm.newObject(klass);
    }

    // x compare y
    private static int compareLong(long y, long x) {
        return Long.compare(x, y);
    }

    private static int compareFloatGreater(float y, float x) {
        return (x < y ? -1 : ((x == y) ? 0 : 1));
    }

    private static int compareFloatLess(float y, float x) {
        return (x > y ? 1 : ((x == y) ? 0 : -1));
    }

    private static int compareDoubleGreater(double y, double x) {
        return (x < y ? -1 : ((x == y) ? 0 : 1));
    }

    private static int compareDoubleLess(double y, double x) {
        return (x > y ? 1 : ((x == y) ? 0 : -1));
    }

    private StaticObject nullCheck(StaticObject value) {
        if (StaticObject.isNull(value)) {
            CompilerDirectives.transferToInterpreter();
            // TODO(peterssen): Profile whether null was hit or not.
            Meta meta = method.getDeclaringClass().getContext().getMeta();
            throw meta.throwEx(NullPointerException.class);
        }
        return value;
    }

    private static int checkNonZero(int value) {
        if (value != 0) {
            return value;
        }
        CompilerDirectives.transferToInterpreter();
        throw EspressoLanguage.getCurrentContext().getMeta().throwEx(ArithmeticException.class, "/ by zero");
    }

    private static long checkNonZero(long value) {
        if (value != 0L) {
            return value;
        }
        CompilerDirectives.transferToInterpreter();
        throw EspressoLanguage.getCurrentContext().getMeta().throwEx(ArithmeticException.class, "/ by zero");
    }

    // endregion

    private Klass resolveType(@SuppressWarnings("unused") int opcode, char cpi) {
        // TODO(peterssen): Check opcode.
        ConstantPool pool = getConstantPool();
        return pool.classAt(cpi).resolve(pool, cpi);
    }

    private FieldInfo resolveField(@SuppressWarnings("unused") int opcode, char cpi) {
        // TODO(peterssen): Check opcode.
        ConstantPool pool = getConstantPool();
        return pool.fieldAt(cpi).resolve(pool, cpi);
    }

    private void putField(OperandStack stack, FieldInfo field, boolean isStatic) {
        fieldWrites.inc();
        assert Modifier.isStatic(field.getFlags()) == isStatic;

        // Arrays do not have fields, the receiver can only be a StaticObject.
        if (isStatic) {
            field.getDeclaringClass().initialize();
        }

        Supplier<StaticObject> receiver = () -> isStatic
                        ? (field.getDeclaringClass()).getStatics() /* static storage */
                        : nullCheck(stack.popObject());

        switch (field.getKind()) {
            case Boolean:
                vm.setFieldBoolean((stack.popInt() == 1 ? true : false), receiver.get(), field);
                break;
            case Byte:
                vm.setFieldByte((byte) stack.popInt(), receiver.get(), field);
                break;
            case Char:
                vm.setFieldChar((char) stack.popInt(), receiver.get(), field);
                break;
            case Short:
                vm.setFieldShort((short) stack.popInt(), receiver.get(), field);
                break;
            case Int:
                vm.setFieldInt(stack.popInt(), receiver.get(), field);
                break;
            case Double:
                vm.setFieldDouble(stack.popDouble(), receiver.get(), field);
                break;
            case Float:
                vm.setFieldFloat(stack.popFloat(), receiver.get(), field);
                break;
            case Long:
                vm.setFieldLong(stack.popLong(), receiver.get(), field);
                break;
            case Object:
                // Arrays do not have fields, the receiver can only be a StaticObject.
                vm.setFieldObject(stack.popObject(), receiver.get(), field);
                break;
            default:
                CompilerDirectives.transferToInterpreter();
                throw EspressoError.shouldNotReachHere("unexpected kind");
        }
    }

    private void getField(OperandStack stack, FieldInfo field, boolean isStatic) {
        fieldReads.inc();
        if (isStatic) {
            field.getDeclaringClass().initialize();
        }
        assert Modifier.isStatic(field.getFlags()) == isStatic;
        // Arrays do not have fields, the receiver can only be a StaticObject.
        StaticObject receiver = isStatic
                        ? field.getDeclaringClass().getStatics() /* static storage */
                        : nullCheck(stack.popObject());
        switch (field.getKind()) {
            case Boolean:
                stack.pushInt(vm.getFieldBoolean(receiver, field) ? 1 : 0);
                break;
            case Byte:
                stack.pushInt(vm.getFieldByte(receiver, field));
                break;
            case Char:
                stack.pushInt(vm.getFieldChar(receiver, field));
                break;
            case Short:
                stack.pushInt(vm.getFieldShort(receiver, field));
                break;
            case Int:
                stack.pushInt(vm.getFieldInt(receiver, field));
                break;
            case Double:
                stack.pushDouble(vm.getFieldDouble(receiver, field));
                break;
            case Float:
                stack.pushFloat(vm.getFieldFloat(receiver, field));
                break;
            case Long:
                stack.pushLong(vm.getFieldLong(receiver, field));
                break;
            case Object:
                stack.pushObject(vm.getFieldObject(receiver, field));
                break;
            default:
                CompilerDirectives.transferToInterpreter();
                throw EspressoError.shouldNotReachHere("unexpected kind");
        }
    }

    @Override
    public String toString() {
        return method.getDeclaringClass().getName() + "." + method.getName() + method.getSignature().toString();
    }

    @Override
    public Meta.Method getOriginalMethod() {
        return meta(method);
    }
}
