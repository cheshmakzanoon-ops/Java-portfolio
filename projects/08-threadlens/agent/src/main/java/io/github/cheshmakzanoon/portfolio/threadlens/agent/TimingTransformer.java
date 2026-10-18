package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import io.github.cheshmakzanoon.portfolio.threadlens.probe.MethodRegistry;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/** Startup only. Catch-all is appended after original handlers, preserving their precedence. */
public final class TimingTransformer implements ClassFileTransformer {
  private static final Type PROBE =
      Type.getObjectType("io/github/cheshmakzanoon/portfolio/threadlens/probe/TimingProbe");
  private final InstrumentationPolicy policy;
  private final MethodRegistry registry;

  public TimingTransformer(InstrumentationPolicy policy, MethodRegistry registry) {
    this.policy = policy;
    this.registry = registry;
  }

  @Override
  public byte[] transform(
      Module module,
      ClassLoader loader,
      String name,
      Class<?> redefining,
      ProtectionDomain domain,
      byte[] bytes) {
    if (redefining != null || !policy.selectClass(module, loader, name)) return null;
    try {
      ClassReader reader = new ClassReader(bytes);
      if ((reader.getAccess()
              & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE))
          != 0) {
        policy.skip("nonOrdinaryClass");
        return null;
      }
      SafeHierarchy hierarchy = new SafeHierarchy(loader, reader);
      ClassWriter writer =
          new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
              return hierarchy.common(a, b);
            }
          };
      boolean[] changed = {false};
      reader.accept(
          new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String method,
                String descriptor,
                String signature,
                String[] exceptions) {
              MethodVisitor delegate =
                  super.visitMethod(access, method, descriptor, signature, exceptions);
              if (!policy.selectMethod(access, method)) return delegate;
              int id = registry.register(name.replace('/', '.') + "." + method + descriptor);
              if (id < 0) {
                policy.skip("methodCeiling");
                return delegate;
              }
              changed[0] = true;
              return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, descriptor) {
                private int start;
                private final Label boundary = new Label(),
                    end = new Label(),
                    handler = new Label();

                @Override
                protected void onMethodEnter() {
                  invokeStatic(PROBE, new Method("enter", "()J"));
                  start = newLocal(Type.LONG_TYPE);
                  storeLocal(start);
                  mark(boundary);
                }

                private void timing(boolean exceptional) {
                  push(id);
                  loadLocal(start);
                  push(exceptional);
                  invokeStatic(PROBE, new Method("exit", "(IJZ)V"));
                }

                @Override
                protected void onMethodExit(int opcode) {
                  if (opcode != ATHROW) timing(false);
                }

                @Override
                public void visitMaxs(int stack, int locals) {
                  mark(end);
                  visitTryCatchBlock(boundary, end, handler, "java/lang/Throwable");
                  mark(handler);
                  int thrown = newLocal(Type.getType(Throwable.class));
                  storeLocal(thrown);
                  timing(true);
                  loadLocal(thrown);
                  throwException();
                  super.visitMaxs(stack, locals);
                }
              };
            }
          },
          ClassReader.EXPAND_FRAMES);
      return changed[0] ? writer.toByteArray() : null;
    } catch (MethodTooLargeException tooLarge) {
      policy.skip("oversizedMethod");
      return null;
    } catch (RuntimeException | LinkageError recoverable) {
      policy.skip("unsafeTransformation");
      return null;
    }
  }
}
