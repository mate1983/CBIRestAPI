package org.cbir.retrieval.web.rest;

import com.codahale.metrics.annotation.Timed;
import org.cbir.retrieval.security.AuthoritiesConstants;
import org.cbir.retrieval.service.RetrievalService;
import org.cbir.retrieval.service.exception.*;
import org.cbir.retrieval.web.rest.dto.StorageJSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import retrieval.server.RetrievalServer;
import retrieval.storage.Storage;
import retrieval.storage.exception.AlreadyIndexedException;
import retrieval.storage.exception.NoValidPictureException;

import javax.annotation.security.RolesAllowed;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * REST controller for managing images.
 */
@RestController
@RequestMapping("/api")
public class ImageResource {

    private final Logger log = LoggerFactory.getLogger(ImageResource.class);

    @Inject
    private RetrievalService retrievalService;

    @RequestMapping(value = "/storages/{id}/images",
        method = RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    @RolesAllowed(AuthoritiesConstants.USER)
    ResponseEntity<Map> getByStorage(@PathVariable Long id,@RequestParam String storage) throws ResourceNotFoundException {
        log.debug("REST request to get image : {}");

        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();

        Storage storageImage = retrievalServer.getStorage(storage);
        if(storageImage==null)
            throw new ResourceNotFoundException("Storage "+ storage +" cannot be found!");

        Map<String,String> properties = storageImage.getProperties(id);
        if(properties==null)
            throw new ResourceNotFoundException("Image "+ id +" cannot be found on storage "+storage+" !");

        return new ResponseEntity<>(properties, HttpStatus.OK);
    }

    @RequestMapping(value = "/images/{id}",
        method = RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    @RolesAllowed(AuthoritiesConstants.USER)
    ResponseEntity<Map> get(@PathVariable Long id) throws ResourceNotFoundException {
        log.debug("REST request to get image : {}");

        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();

        Optional<Map<String,String>> properties =
            Optional.of(retrievalServer.getStorageList()
                .parallelStream()
                .map(x -> x.getProperties(id))
                .filter(x -> x != null)
                .reduce((previous, current) -> current)
                .get());

        if(!properties.isPresent())
            throw new ResourceNotFoundException("Image "+ id +" cannot be found !");

        return new ResponseEntity<>(properties.get(), HttpStatus.OK);
    }


    @RequestMapping(value="/images",
        method = RequestMethod.GET,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Timed
    @RolesAllowed(AuthoritiesConstants.USER)
    ResponseEntity<List> getAll() throws CBIRException{
        log.debug("REST request to list images : {}");

        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();

        try {
            List<Map<String,String>> map = retrievalServer.getInfos()
                .entrySet()
                .stream()
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
            return new ResponseEntity<>(map, HttpStatus.OK);
        } catch(Exception e) {
            throw new CBIRException(e.toString(),HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/images",
        method = RequestMethod.POST,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<Map<String,String>> create(
        @RequestParam(value="id") Long idImage,
        @RequestParam(value="storage") String idStorage,
        @RequestParam String keys,
        @RequestParam String values,
        @RequestParam(defaultValue = "false") Boolean async,//http://stackoverflow.com/questions/17693435/how-to-give-default-date-values-in-requestparam-in-spring
        MultipartFile imageBytes
    ) throws CBIRException
    {
        log.debug("REST request to create image : {}", idImage);
        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();

        Storage storage;
        if(idStorage==null) {
            storage = retrievalServer.getNextStorage();
        } else {
            storage = retrievalServer.getStorage(idStorage,true);
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes.getBytes()));
        } catch(IOException ex) {
            throw new ResourceNotValidException("Image not valid:"+ex.toString());
        }


        Map<String,String> properties = new TreeMap<>();
        if(keys!=null) {
            String[] keysArray = keys.split(";");
            String[] valuesArray = values.split(";");

            if(keysArray.length!=valuesArray.length) {
                throw new ParamsNotValidException("keys.size()!=values.size()");
            }

            for(int i=0;i<keysArray.length;i++) {
                properties.put(keysArray[i],valuesArray[i]);
            }
        }

        //index picture
        Long id;
        try {
            if (async) {
                id = storage.addToIndexQueue(image, idImage, properties);
            } else {
                id = storage.indexPicture(image, idImage, properties);
            }
        } catch(AlreadyIndexedException e) {
            throw new ResourceAlreadyExistException("Image "+ idImage + "already exist in storage "+idStorage);
        }catch(NoValidPictureException e) {
            throw new ResourceNotValidException("Cannot insert image:"+e.toString());
        }catch(Exception e) {
            throw new CBIRException("Cannot insert image:"+e.toString(),HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(storage.getProperties(id), HttpStatus.OK);
    }

    @RequestMapping(value = "/storages/{storage}/images/{id}",
        method = RequestMethod.DELETE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public void delete(
            @PathVariable Long id,
            @PathVariable String storage
    ) throws Exception {
        log.debug("REST request to delete storage : {}", id);
        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();

        Storage storageImage = retrievalServer.getStorage(storage);
        if(storageImage==null)
            throw new ResourceNotFoundException("Storage "+ storage +" cannot be found!");

        if(storageImage.isPictureInIndex(id))
            throw new ResourceNotFoundException("Image "+ id +" cannot be found on storage "+storage+" !");

        try {
            storageImage.deletePicture(id);
        } catch(Exception e) {
            log.error(e.getMessage());
            throw new CBIRException("Cannot delete image:"+e.toString(),HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }













//    /**
//     * POST -> Create a new storage.
//     */
//    @RequestMapping(value = "/storages",
//        method = RequestMethod.POST,
//        produces = MediaType.APPLICATION_JSON_VALUE)
//    @Timed
//    public void create(@RequestBody StorageJSON storageJSON) throws RessourceAlreadyExistException{
//        log.debug("REST request to save storage : {}", storageJSON.getId());
//        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();
//
//        if(retrievalServer.getStorage(storageJSON.getId())!=null) {
//            throw new RessourceAlreadyExistException("Storage "+ storageJSON.getId() +" already exist!");
//        }
//
//        try {
//            retrievalServer.createStorage(storageJSON.getId());
//        } catch(Exception e) {
//              log.error(e.getMessage());
//        }
//
//    }
//
//    @RequestMapping(value="/storages",
//        method = RequestMethod.GET,
//        produces = MediaType.APPLICATION_JSON_VALUE
//    )
//    @Timed
//    @RolesAllowed(AuthoritiesConstants.USER)
//    List<StorageJSON> getAll() {
//        log.debug("REST request to list storages : {}");
//
//        RetrievalServer retrievalServer = retrievalService.getRetrievalServer();
//
//        List<StorageJSON> storagesJSON =
//            retrievalServer.getStorageList()
//                .stream()
//                .map(StorageJSON::new)
//                .collect(Collectors.toList());
//
//        return storagesJSON;
//    }
//

//
//


}
