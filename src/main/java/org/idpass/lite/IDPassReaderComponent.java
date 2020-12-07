package org.idpass.lite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.protobuf.ByteString;
import io.mosip.kernel.core.pdfgenerator.exception.PDFGeneratorException;
import io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.pdfgenerator.itext.constant.PDFGeneratorExceptionCodeConstant;
import io.mosip.print.constant.ApiName;
import io.mosip.print.constant.UinCardType;
import io.mosip.print.dto.ErrorDTO;
import io.mosip.print.dto.PDFSignatureRequestDto;
import io.mosip.print.dto.SignatureResponseDto;
import io.mosip.print.exception.ApisResourceAccessException;
import io.mosip.print.exception.PDFSignatureException;
import io.mosip.print.service.PrintRestClientService;
import io.mosip.registration.print.core.http.RequestWrapper;
import io.mosip.registration.print.core.http.ResponseWrapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.api.proto.Ident;
import org.idpass.lite.exceptions.IDPassException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.FileSystem;

import com.github.jaiimageio.jpeg2000.impl.J2KImageReader;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.mosip.print.service.impl.PrintServiceImpl.DATETIME_PATTERN;

/**
 * Spring boot singleton component execution wrapper of
 * an IDPassReader instance
 */

@Component
public class IDPassReaderComponent
{
    /*
    In-memory file system for faster creation of temporary files
     */
    private static FileSystem memfs = Jimfs.newFileSystem(Configuration.unix());
    private static Path signaturePage;
    private static Path mempath;

    public static IDPassReader reader;

    @Autowired
    private PDFGenerator pdfGenerator;

    @Autowired
    IDPassliteConfig m_config;

    @Autowired
    private Environment env;

    @Autowired
    private PrintRestClientService<Object> restClientService;

    @Value("${mosip.registration.processor.print.service.uincard.signature.reason}")
    private String reason;

    ObjectMapper mapper = new ObjectMapper();

    /**
     * Instantiates IDPassReader reader with a particular configuration
     *
     * @throws IDPassException Standard exception
     * @throws IOException Standard exception
     */
    public IDPassReaderComponent(IDPassliteConfig config)
            throws IDPassException, IOException
    {
        if (reader == null) {
            InputStream is = IDPassReaderComponent.class.getClassLoader().getResourceAsStream(config.getP12File());

            // Initialize reader
            reader = new IDPassReader(
                    config.getStorePrefix(), is,
                    config.getStorePassword(), config.getKeyPassword());

            reader.setDetailsVisible(config.getVisibleFields());

            /*
            In-memory file system is used to construct the 3-pages pdf
            to be send for signing
             */
            Path jimPath = memfs.getPath("");
            Path sigpage = jimPath.resolve("signaturepage.pdf");
            byte[] sig = IDPassReaderComponent.class.getClassLoader()
                .getResourceAsStream("signaturepage.pdf").readAllBytes();
            Files.write(sigpage, sig);
            signaturePage = jimPath.resolve("signaturepage.pdf");
            mempath = memfs.getPath("");
        }
    }

    /**
     * Returns a PNG image QR code representation as a byte[] array,
     * from the given inputs:
     *
     * @param cs The credential subject input json
     * @param pincode The IDPASS LITE pin code
     * @param photob64 A facial photo image
     * @return Returns PNG QR code of the generated IDPASS LITE card
     */
    public IDPassLiteDTO generateQrCode(String cs, String photob64, String pincode)
            throws IOException {
        IDPassLiteDTO ret = new IDPassLiteDTO();
        IdentFieldsConstraint idfc = null;

        try {
            idfc = (IdentFieldsConstraint) IdentFields.parse(cs, IdentFieldsConstraint.class);
            ret.setIdfc(idfc);
            if (idfc == null || !idfc.isValid()) { // in terms of identfieldsconstraint.json
                return null;
            }

        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            return null;
        }

        Ident.Builder identBuilder = idfc.newIdentBuilder();

        identBuilder.setPin(pincode);

        String imageType = photob64.split(",")[0];
        byte[] photo = CryptoUtil.decodeBase64(photob64.split(",")[1]);
        if (/* imageType.equals("data:image/x-jp2;base64") */ true) { /// TODO: Already raised this issue
            photo = convertJ2KToJPG(photo);
        }

        if (photo != null) {
            identBuilder.setPhoto(ByteString.copyFrom(photo));
            ret.setFacePhotob64(CryptoUtil.encodeBase64String(photo));
        }

        /* Populate Ident fields from idf object */

        Ident ident = identBuilder.build();
        byte[] qrcodeId = null;

        try {
            Card card = reader.newCard(ident, null);
            BufferedImage bi = card.asQRCode();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", bos);
            qrcodeId = bos.toByteArray();
            ret.setQrCodeBytes(qrcodeId);
            ret.setSvg(card.asQRCodeSVG().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | IDPassException e) {
            return null;
        }

        return  ret;
    }

    /**
     * Call editor.idpass.org to generate ID PASS Lite PDF card
     * @return Returns pdf bytes array
     * @throws IOException Standard exception
     */

    public byte[] editorGenerate(IDPassLiteDTO sd)
            throws IOException
    {
        byte[] pdfbytes = null;
        IdentFieldsConstraint m_idfc = sd.getIdfc();

        ObjectNode front = mapper.createObjectNode();
        front.put("identification_number",m_idfc.getUIN());
        front.put("full_name", m_idfc.getFullName());
        front.put("sex",m_idfc.getGender() == 1 ? "Female" : "Male");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/d"); /// TODO: move to config? or list of possible combinations
        if (m_idfc.getDateOfBirth() != null) { /// TODO: generalized these 'if' checks
            front.put("birth_date", m_idfc.getDateOfBirth().format(formatter));
        }
        LocalDate issuanceDate = LocalDate.now();
        String issue_date = issuanceDate.format(formatter);
        front.put("issue_date",issue_date);
        String exp = issuanceDate.plusYears(m_config.getExpireYears()).format(formatter);
        front.put("expiry_date", exp);
        front.put("qrcode", "data:image/jpeg;base64," + sd.getFacePhotob64());

        String svgqrcode = CryptoUtil.encodeBase64String(sd.getSvg());

        ObjectNode back = mapper.createObjectNode();
        back.put("qrcode", "data:image/svg+xml;base64," + svgqrcode);

        ObjectNode fields = mapper.createObjectNode();
        fields.set("front", front);
        fields.set("back", back);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("create_qr_code", false);
        payload.set("fields", fields);

        String jsonPayload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

        //////////////
        RestTemplate restTemplate = new RestTemplate();
        String uri = m_config.getEditorUrl();
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<String, String>();
        headers.add("Content-Type", MediaType.APPLICATION_JSON.toString());

        HttpEntity<String> request = new HttpEntity<String>(jsonPayload, headers);

        String response = restTemplate.postForObject(uri, request, String.class);
        JsonNode node = mapper.readTree(response);
        String blob = node.get("files").get("pdf").asText();
        String b64 = blob.split(",")[1];
        pdfbytes = CryptoUtil.decodeBase64(b64);
        /////////////

        return pdfbytes;
    }

    /**
     * This method is a modified from UinCardGeneratorImpl::generateUinCard
     * as this invokes a REST call to editor.idpass.org to generate the pdf
     * that is about to be send to MOSIP backend for signature
     *
     * @param in Template. Not used here
     * @param type Card type
     * @param password password
     * @return Returns pdf bytes of signed pdf
     * @throws ApisResourceAccessException standard exception
     */

    public byte[] generateUinCard(InputStream in, UinCardType type, String password, IDPassLiteDTO sd)
            throws ApisResourceAccessException
    {
        byte[] pdfSignatured=null;
        try {
            // Calls editor.idpass.org REST API to generate initial PDF
            byte[] pdfbuf = editorGenerate(sd);
            Instant instant = java.time.Instant.now();
            long es = instant.getEpochSecond();
            int en = instant.getNano();
            String tmp = "card" + es + "-" + en + ".pdf";
            Path unsignedPdf = mempath.resolve(tmp);
            Files.write(unsignedPdf, pdfbuf); // write to in-memory fs

            List<URL> pdfList = new ArrayList<>();
            pdfList.add(unsignedPdf.toUri().toURL());
            pdfList.add(signaturePage.toUri().toURL());
            byte[] threepages = pdfGenerator.mergePDF(pdfList);
            Files.deleteIfExists(unsignedPdf);

            PDFSignatureRequestDto request = new PDFSignatureRequestDto(5, 2, 232, 72, reason, 3, password);

            request.setApplicationId("KERNEL");
            request.setReferenceId("SIGN");
            request.setData(CryptoUtil.encodeBase64String(threepages));
            DateTimeFormatter format = DateTimeFormatter.ofPattern(env.getProperty(DATETIME_PATTERN));
            LocalDateTime localdatetime = LocalDateTime
                    .parse(DateUtils.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN)), format);

            request.setTimeStamp(DateUtils.getUTCCurrentDateTimeString());
            RequestWrapper<PDFSignatureRequestDto> requestWrapper = new RequestWrapper<>();

            requestWrapper.setRequest(request);
            requestWrapper.setRequesttime(localdatetime);
            ResponseWrapper<?> responseWrapper;
            SignatureResponseDto signatureResponseDto;

            responseWrapper= (ResponseWrapper<?>)restClientService.postApi(ApiName.PDFSIGN, null, null,
                    requestWrapper, ResponseWrapper.class,MediaType.APPLICATION_JSON);


            if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
                ErrorDTO error = responseWrapper.getErrors().get(0);
                throw new PDFSignatureException(error.getMessage());
            }
            signatureResponseDto = mapper.readValue(mapper.writeValueAsString(responseWrapper.getResponse()),
                    SignatureResponseDto.class);

            pdfSignatured = CryptoUtil.decodeBase64(signatureResponseDto.getData());

        } catch (IOException | PDFGeneratorException e) {
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    e.getMessage() + ExceptionUtils.getStackTrace(e));
        }
        catch (ApisResourceAccessException e) {
            e.printStackTrace();
        }

        return pdfSignatured;
    }

    // Notes: copied from 'mosip-functional-tests' repo
    private static byte[] convertJ2KToJPG(byte[] jp2Data) {
        byte[] jpgImg = null;
        J2KImageReader j2kImageReader = new J2KImageReader(null);
        try {
            j2kImageReader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(jp2Data)));
            ImageReadParam imageReadParam = j2kImageReader.getDefaultReadParam();
            BufferedImage image = j2kImageReader.read(0, imageReadParam);
            ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
            ImageIO.write(image, "JPG", imgBytes);
            jpgImg = imgBytes.toByteArray();
        } catch (IOException e) {
            return null;
        }

        return jpgImg;
    }
}
