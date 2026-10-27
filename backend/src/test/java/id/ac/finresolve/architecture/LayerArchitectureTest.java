package id.ac.finresolve.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the layered dependency direction independently of Spring component scanning. */
@AnalyzeClasses(
        packages = "id.ac.finresolve",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

    private static final String PRESENTATION = "id.ac.finresolve.presentation..";
    private static final String SERVICE = "id.ac.finresolve.service..";
    private static final String REPOSITORY = "id.ac.finresolve.repository..";
    private static final String DOMAIN = "id.ac.finresolve.domain..";
    private static final String CONFIG = "id.ac.finresolve.config..";

    @ArchTest
    static final ArchRule presentation_must_not_access_repository = noClasses()
            .that()
            .resideInAPackage(PRESENTATION)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(REPOSITORY);

    @ArchTest
    static final ArchRule no_layer_may_depend_back_on_presentation = noClasses()
            .that()
            .resideInAnyPackage(SERVICE, REPOSITORY, DOMAIN, CONFIG)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(PRESENTATION);

    @ArchTest
    static final ArchRule repository_must_not_access_service_or_presentation = noClasses()
            .that()
            .resideInAPackage(REPOSITORY)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, PRESENTATION);

    @ArchTest
    static final ArchRule service_must_not_use_jdbc = noClasses()
            .that()
            .resideInAPackage(SERVICE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.sqlite..");

    @ArchTest
    static final ArchRule domain_must_stay_framework_and_layer_independent = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
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
                    PRESENTATION,
                    SERVICE,
                    REPOSITORY,
                    CONFIG);
}
