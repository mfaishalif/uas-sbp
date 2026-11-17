package id.ac.finresolve.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * Enforces role boundaries independently of folder layout.
 *
 * <p>Feature folders mix controllers, services, repositories, and domain models, so roles are
 * listed explicitly where no consistent naming convention exists.
 */
@AnalyzeClasses(
        packages = "id.ac.finresolve",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

    private static final Set<String> CONTROLLERS = Set.of(
            "id.ac.finresolve.cases.CasesController",
            "id.ac.finresolve.category.CategoriesController",
            "id.ac.finresolve.importing.ImportsController",
            "id.ac.finresolve.search.SearchController",
            "id.ac.finresolve.system.SystemController",
            "id.ac.finresolve.shared.error.ApiErrorController");

    private static final Set<String> SERVICES = Set.of(
            "id.ac.finresolve.cases.CaseReadService",
            "id.ac.finresolve.cases.RetentionService",
            "id.ac.finresolve.category.CategoryService",
            "id.ac.finresolve.importing.CfpbCsvParser",
            "id.ac.finresolve.importing.ImportService",
            "id.ac.finresolve.search.SearchService",
            "id.ac.finresolve.system.DemoReadiness",
            "id.ac.finresolve.system.HealthService");

    private static final Set<String> REPOSITORIES = Set.of(
            "id.ac.finresolve.cases.CaseRepository",
            "id.ac.finresolve.category.CategoryRepository",
            "id.ac.finresolve.importing.ImportRepository",
            "id.ac.finresolve.system.SystemRepository");

    private static final Set<String> DOMAIN_MODELS = Set.of(
            "id.ac.finresolve.cases.CaseCapacity",
            "id.ac.finresolve.cases.CasePage",
            "id.ac.finresolve.cases.CaseRecord",
            "id.ac.finresolve.cases.CaseRules",
            "id.ac.finresolve.cases.ComplaintInput",
            "id.ac.finresolve.cases.InputRules",
            "id.ac.finresolve.cases.RetainCommand",
            "id.ac.finresolve.cases.RetentionRules",
            "id.ac.finresolve.category.CategoryValues",
            "id.ac.finresolve.importing.BatchRecord",
            "id.ac.finresolve.importing.ImportMetadataValue",
            "id.ac.finresolve.importing.ImportRowProblem",
            "id.ac.finresolve.similarity.CategoryScorer",
            "id.ac.finresolve.similarity.EnglishTokenizer",
            "id.ac.finresolve.similarity.PhraseDictionary",
            "id.ac.finresolve.similarity.SimilarityEngine",
            "id.ac.finresolve.similarity.SimilarityMode",
            "id.ac.finresolve.similarity.SimilarityResult",
            "id.ac.finresolve.similarity.SimilarityScorer",
            "id.ac.finresolve.similarity.SimilaritySettings",
            "id.ac.finresolve.similarity.TextRules",
            "id.ac.finresolve.similarity.TfidfSnapshot",
            "id.ac.finresolve.shared.error.BusinessException",
            "id.ac.finresolve.shared.text.UnicodeText");

    private static final String[] LEGACY_LAYERS = {
        "id.ac.finresolve.presentation..",
        "id.ac.finresolve.domain..",
        "id.ac.finresolve.service..",
        "id.ac.finresolve.repository.."
    };

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories = noClasses()
            .that(in(CONTROLLERS))
            .should()
            .dependOnClassesThat(in(REPOSITORIES));

    @ArchTest
    static final ArchRule services_must_not_use_jdbc = noClasses()
            .that(in(SERVICES))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.sqlite..");

    @ArchTest
    static final ArchRule domain_models_must_stay_framework_and_boundary_independent = noClasses()
            .that(in(DOMAIN_MODELS))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "java.sql..",
                    "javax.sql..",
                    "org.sqlite..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..",
                    "id.ac.finresolve.api..",
                    "id.ac.finresolve.config..",
                    "id.ac.finresolve.shared.db..");

    @ArchTest
    static final ArchRule moved_code_must_not_reference_legacy_layer_packages = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(LEGACY_LAYERS);

    private static DescribedPredicate<JavaClass> in(Set<String> classNames) {
        return DescribedPredicate.describe(
                "one of " + classNames, javaClass -> classNames.contains(javaClass.getName()));
    }
}
