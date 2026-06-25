package com.example.complaints.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Package-boundary guardrails. See TECHNICAL_DESIGN.md §3 + §16.7.
 *
 * <p>The cross-module repository rule uses ArchUnit's {@code Slices} API because
 * package-pattern wildcards do not establish equality across captures — a plain
 * {@code (*)..service..  →  (*)..repository..} rule would falsely flag legitimate
 * same-module dependencies.</p>
 *
 * <p>Stage 21.2.6 added the four shape rules at the bottom (records / mappers /
 * repositories / no-field-injection). Each was verified to match real classes
 * before being added — {@code archRule.failOnEmptyShould=true} ensures any future
 * package rename that empties a rule fails the build instead of silently passing.</p>
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

    // ---------- Stage 21.2.6 — shape rules ----------

    /**
     * Hard rule (copilot-instructions.md): "DTOs are Java 21 {@code record} types."
     * Enums in {@code ..dto..} (if any are ever added) are allowed because they're
     * value types, not data carriers.
     */
    @ArchTest
    static final ArchRule dtos_must_be_records_or_enums =
            classes().that().resideInAPackage("..dto..")
                    .and().areTopLevelClasses()
                    .should(new com.tngtech.archunit.lang.ArchCondition<JavaClass>("be a record or an enum") {
                        @Override
                        public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                            boolean ok = item.isRecord() || item.isEnum();
                            if (!ok) {
                                events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                                        item.getFullName() + " is in ..dto.. but is neither a record nor an enum"));
                            }
                        }
                    })
                    .because("Hard rule: DTOs are Java 21 record types (copilot-instructions.md).");

    /**
     * Hard rule: "Hand-written {@code *Mapper} classes for entity ↔ DTO." Class-suffix
     * convention {@code Mapper} is required when the type fits the role.
     */
    @ArchTest
    static final ArchRule mappers_must_end_with_mapper_suffix =
            classes().that().resideInAPackage("..mapper..")
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("Mapper")
                    .because("Naming convention: mapper classes carry the Mapper suffix (copilot-instructions.md).");

    /**
     * Defensive: repository packages must contain only Spring Data interfaces. Catches
     * the "let me just put a helper class here" drift before it lands.
     */
    @ArchTest
    static final ArchRule repositories_must_be_spring_data_interfaces =
            classes().that().resideInAPackage("..repository..")
                    .and().areTopLevelClasses()
                    .should().beInterfaces()
                    .andShould().beAssignableTo(Repository.class)
                    .because("Repository packages hold Spring Data interfaces only; helpers belong in service.");

    /**
     * Hard rule: "Constructor injection only — no field injection." Final fields +
     * Lombok {@code @RequiredArgsConstructor} on services / controllers / components.
     */
    @ArchTest
    static final ArchRule no_field_injection_via_autowired =
            noFields().should().beAnnotatedWith(Autowired.class)
                    .because("Hard rule: constructor injection only (copilot-instructions.md).");

    /**
     * Anchor so the rule above doesn't silently pass on an empty match-set after a
     * future package rename — ArchUnit's {@code failOnEmptyShould=true} catches that
     * for {@code classes()} rules, but the {@code fields()} variant needs us to prove
     * the universe is non-empty.
     */
    @ArchTest
    static final ArchRule field_universe_is_non_empty =
            fields().that().areDeclaredInClassesThat().resideInAPackage("com.example.complaints..")
                    .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaField>("exist") {
                        @Override
                        public void check(com.tngtech.archunit.core.domain.JavaField item,
                                          com.tngtech.archunit.lang.ConditionEvents events) {
                            // Always passes — purpose is to confirm fields() returns non-empty.
                        }
                    })
                    .because("Anchor for no_field_injection_via_autowired — proves fields() is non-empty.");
}
