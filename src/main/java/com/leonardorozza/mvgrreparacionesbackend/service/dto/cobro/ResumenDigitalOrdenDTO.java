package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Resumen operativo de una orden. No representa una factura ni un comprobante fiscal.
 *
 * <p>Los accessors JSON del final conservan la forma plana histórica de {@link ReciboDTO}
 * durante la migración del frontend. La estructura anidada es el contrato canónico nuevo.</p>
 */
@JsonPropertyOrder({
        "numeroOrden", "codigoSeguimiento", "fecha", "estado",
        "taller", "cliente", "equipo", "detalle", "importes", "pagos",
        "datosCobro", "documentoFiscal", "leyenda",
        "reparacionId", "tallerNombre", "tallerTelefono",
        "clienteNombre", "clienteApellido", "clienteTelefono",
        "equipoMarca", "equipoModelo", "descripcionProblema",
        "repuestos", "manoDeObra", "totalRepuestos",
        "total", "cobrado", "saldo", "excedente", "requiereRevision", "pagado"
})
public record ResumenDigitalOrdenDTO(
        String numeroOrden,
        String codigoSeguimiento,
        LocalDateTime fecha,
        EstadoReparacion estado,
        TallerDTO taller,
        ClienteDTO cliente,
        EquipoDTO equipo,
        DetalleDTO detalle,
        ImportesDTO importes,
        List<PagoDTO> pagos,
        DatosCobroPublicosDTO datosCobro,
        boolean documentoFiscal,
        String leyenda,
        Long reparacionId
) {

    public ResumenDigitalOrdenDTO {
        pagos = List.copyOf(pagos);
    }

    public record TallerDTO(String nombre, String telefono) {
    }

    public record ClienteDTO(String nombre, String apellido, String telefono) {
    }

    public record EquipoDTO(String marca, String modelo, String descripcionProblema) {
    }

    public record DetalleDTO(
            List<RepuestoDTO> repuestos,
            BigDecimal manoDeObra,
            BigDecimal totalRepuestos
    ) {
        public DetalleDTO {
            repuestos = List.copyOf(repuestos);
        }
    }

    public record RepuestoDTO(
            String nombre,
            int cantidad,
            BigDecimal precioUnitario,
            BigDecimal subtotal
    ) {
    }

    public record ImportesDTO(
            BigDecimal total,
            BigDecimal cobrado,
            BigDecimal saldo,
            BigDecimal excedente,
            boolean requiereRevision,
            boolean pagado
    ) {
    }

    public record PagoDTO(
            LocalDateTime fecha,
            BigDecimal monto,
            MetodoPago metodo,
            String referencia
    ) {
    }

    public record DatosCobroPublicosDTO(
            String alias,
            String titular,
            String entidad,
            boolean qrDisponible,
            String qrVersion
    ) {
    }

    @JsonProperty("tallerNombre")
    public String tallerNombre() {
        return taller.nombre();
    }

    @JsonProperty("tallerTelefono")
    public String tallerTelefono() {
        return taller.telefono();
    }

    @JsonProperty("clienteNombre")
    public String clienteNombre() {
        return cliente.nombre();
    }

    @JsonProperty("clienteApellido")
    public String clienteApellido() {
        return cliente.apellido();
    }

    @JsonProperty("clienteTelefono")
    public String clienteTelefono() {
        return cliente.telefono();
    }

    @JsonProperty("equipoMarca")
    public String equipoMarca() {
        return equipo.marca();
    }

    @JsonProperty("equipoModelo")
    public String equipoModelo() {
        return equipo.modelo();
    }

    @JsonProperty("descripcionProblema")
    public String descripcionProblema() {
        return equipo.descripcionProblema();
    }

    @JsonProperty("repuestos")
    public List<RepuestoDTO> repuestos() {
        return detalle.repuestos();
    }

    @JsonProperty("manoDeObra")
    public BigDecimal manoDeObra() {
        return detalle.manoDeObra();
    }

    @JsonProperty("totalRepuestos")
    public BigDecimal totalRepuestos() {
        return detalle.totalRepuestos();
    }

    @JsonProperty("total")
    public BigDecimal total() {
        return importes.total();
    }

    @JsonProperty("cobrado")
    public BigDecimal cobrado() {
        return importes.cobrado();
    }

    @JsonProperty("saldo")
    public BigDecimal saldo() {
        return importes.saldo();
    }

    @JsonProperty("excedente")
    public BigDecimal excedente() {
        return importes.excedente();
    }

    @JsonProperty("requiereRevision")
    public boolean requiereRevision() {
        return importes.requiereRevision();
    }

    @JsonProperty("pagado")
    public boolean pagado() {
        return importes.pagado();
    }
}
