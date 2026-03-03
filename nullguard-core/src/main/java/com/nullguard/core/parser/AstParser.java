package com.nullguard.core.parser;

import com.nullguard.core.model.ProjectModel;
import java.nio.file.Path;

public interface AstParser {
    ProjectModel parse(Path projectRoot);
}
