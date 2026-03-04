package com.nullguard.callgraph.resolver;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import java.util.Optional;
public final class MethodResolver {
    public Optional<String> resolve(ProjectModel project, String callerMethodId, String calledMethodName) {
        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel mth : cls.getMethods().values()) {
                        String id = pkg.getPackageName() + "." + cls.getClassName() + "#" + mth.getSignature();
                        String searchName = calledMethodName;
                        if (searchName.contains(".")) {
                            searchName = searchName.substring(searchName.lastIndexOf(".") + 1);
                        }
                        if (mth.getMethodName().equals(searchName) || mth.getSignature().contains(searchName)) {
                            return Optional.of(id);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}
