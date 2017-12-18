# mnsuk-converter-uimaae

This converter installs a specified UIMA pear file and runs the resultant analysis engine (annotator) on text content.

see: https://apps.na.collabserv.com/wikis/home?lang=en-us#!/wiki/W29bb225fbb89_43b4_afc1_efb4b4da13b5/page/mnsuk-converter-uimaae

    
## Deployment
    
After building
1. Copy the mnsuk-converter-uimaae-x.x.x-distrib.zip file from the target folder to the root of your Engine install dir
1. Unzip that archive
1. Ensure that the new directory pearsupport/run is writable by the Engine user (apache)
1. In the Engine Admin Tool, navigate to Management -> Installation -> Repository and click 'unpack' to add the converters's xml node to the repository.
1. Add the node "MNSUK UIMA Analysis Engine from PEAR file" like any other converter to the collection of your choice.

