package com.example.complaints.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Package-boundary guardrails. See TECHNICAL_DESIGN.md §3 + §16.7.
 *
 * <p>The cross-module repository rule uses ArchUnit's {@code Slices} API because
 * package-pattern wildcards do not establish equality across captures — a plain
 * {@code (*)..service..  →  (*)..repository..} rule would falsely flag legitimate
 * same-module dependencies.</p>
 */
@AnalyzeClasses(
        packages = "com.example.complaints",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class PackageBoundaryTest {

    @ArchTest
    static final ArchRule controllers_must_not_touch_repositories =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .because("Controllers must go through services, not touch repositories directly.");

    @ArchTest
    static final ArchRule controllers_must_not_serialize_entities =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..model..").and(DescribedPredicate.<JavaClass>describe(
                                    "are not enums", clazz -> !clazz.isEnum())))
                    .because("Controllers must serialize DTOs, never JPA entities. Enums in "
                            + "..model.. (e.g. UserRole) are value types and safe to cross the wire.");

    /**
     * A module's classes may not depend on another module's {@code ..repository..} package.
     * Cross-module data exchange must go through service interfaces or domain events.
     */
    @ArchTest
    static final ArchRule modules_must_not_call_other_modules_repositories =
            SlicesRuleDefinition.slices()
                    .matching("com.example.complaints.(*)..")
                    .namingSlices("$1")
                    .should().notDependOnEachOther()
                    .ignoreDependency(
                            DescribedPredicate.<JavaClass>alwaysTrue(),
                            DescribedPredicate.not(resideInAPackage("..repository..")))
                    .because("A module's service / controller may not reach into another module's repository.");

    @ArchTest
    static final ArchRule services_must_not_call_other_controllers =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule transactional_belongs_on_services_not_controllers =
            noClasses().that().resideInAPackage("..controller..")
                    .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                    .because("@Transactional belongs on services, never on controllers.");
}
