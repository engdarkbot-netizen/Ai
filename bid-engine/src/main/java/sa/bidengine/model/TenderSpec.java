package sa.bidengine.model;

import java.util.List;

/**
 * الناتج المهيكل لخط الاستخراج: تمثيل كامل لكراسة الشروط.
 * هذا الكائن هو "عقد" النظام كله — كل ما بعده (الصياغة، الامتثال) يقرأ منه.
 */
public record TenderSpec(
        String tenderName,          // اسم المنافسة
        String governmentEntity,    // الجهة الحكومية
        String tenderNumber,        // رقم المنافسة على اعتماد
        String submissionDeadline,  // موعد إقفال التقديم (نص كما ورد — لا تفسّره الآن)
        List<Requirement> requirements,
        List<EvaluationCriterion> evaluationCriteria,
        List<MandatoryDocument> mandatoryDocuments   // المستندات الإلزامية (شهادات، ضمانات...)
) {}
