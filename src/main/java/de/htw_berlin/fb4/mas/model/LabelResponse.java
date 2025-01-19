package de.htw_berlin.fb4.mas.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LabelResponse {

    private SStatus sstatus;
    private String shipmentNo;
    private Label label;
    private QrLabel qrLabel;
    private String routingCode;


}
