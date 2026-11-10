package com.getjobs.worker.dto;

/** 仅有批次计数时，不能把前 N 个岗位推断为成功。 */
public record BatchDeliveryOutcome(Integer succeeded, Integer failed) {
    public DeliveryNotification.Status statusFor(int selectedCount) {
        if (selectedCount > 0 && succeeded != null && succeeded == selectedCount && Integer.valueOf(0).equals(failed)) {
            return DeliveryNotification.Status.SUCCESS;
        }
        if (selectedCount > 0 && Integer.valueOf(0).equals(succeeded) && failed != null && failed == selectedCount) {
            return DeliveryNotification.Status.FAILED;
        }
        return DeliveryNotification.Status.UNCONFIRMED;
    }

    public String describe() {
        return "本批成功 " + (succeeded == null ? "未知" : succeeded) + " 个，未投递 "
                + (failed == null ? "未知" : failed) + " 个；平台仅返回批量计数，部分成功时无法对应具体岗位";
    }
}
