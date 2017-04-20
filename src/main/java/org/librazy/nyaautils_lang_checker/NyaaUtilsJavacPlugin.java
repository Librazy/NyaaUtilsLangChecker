package org.librazy.nyaautils_lang_checker;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

import javax.lang.model.element.ExecutableElement;

public class NyaaUtilsJavacPlugin implements Plugin, TaskListener {
    @Override
    public java.lang.String getName() {
        return NyaaUtilsJavacPlugin.class.getSimpleName();
    }

    @Override
    public void init(JavacTask task, String... args) {
        task.addTaskListener(this);
    }

    @Override
    public void finished(TaskEvent taskEvt) {
        System.out.println(taskEvt.getKind());
        if (taskEvt.getKind() == TaskEvent.Kind.ANALYZE) {
            taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree methodInv, Void v) {
                    ExecutableElement method = (ExecutableElement)TreeInfo.symbol((JCTree) methodInv.getMethodSelect());
                    System.out.println(method);
                    return super.visitMethodInvocation(methodInv, v);
                }
            }, null);
        }
    }

    @Override
    public void started(TaskEvent taskEvt) {
        //No-op
    }

}